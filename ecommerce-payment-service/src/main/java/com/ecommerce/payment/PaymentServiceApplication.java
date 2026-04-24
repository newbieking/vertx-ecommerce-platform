package com.ecommerce.payment;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.discovery.ServiceRegistrar;
import com.ecommerce.common.model.ApiResponse;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付服务 - 异步处理 + 幂等设计
 *
 * 核心特性：
 * - 支付请求进入队列，异步处理
 * - 幂等性：同一订单多次支付只处理一次
 * - 超时取消：支付单创建后 30 分钟未支付自动取消
 */
public class PaymentServiceApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceApplication.class);

    private ServiceRegistrar registrar;
    private EventBus eventBus;

    /**支付单存储*/
    private final Map<String, PaymentOrder> payments = new ConcurrentHashMap<>();
    /** 订单支付状态（幂等控制）*/
    private final Map<String, PaymentStatus> orderPaymentStatus = new ConcurrentHashMap<>();

    /**支付状态*/
    public enum PaymentStatus {
        PENDING,      // 待支付
        PROCESSING,   // 处理中
        SUCCESS,      // 支付成功
        FAILED,       // 支付失败
        CANCELLED,    // 已取消
        TIMEOUT       // 超时
    }

    public static void main(String[] args) {

        VertxOptions options = new VertxOptions()
                .setEventBusOptions(new EventBusOptions()
                        // 集群通信端口（每个服务实例不同，或自动分配）
                        .setHost("localhost")
                        .setPort(0)  // 0 = 自动分配
                );

        Vertx.clusteredVertx(options, res -> {
            if (res.succeeded()) {
                Vertx vertx = res.result();
                vertx.deployVerticle(new PaymentServiceApplication());
                logger.info("Payment service started in cluster mode");
            } else {
                logger.error("Failed to start cluster", res.cause());
            }
        });
//        Launcher.executeCommand("run", PaymentServiceApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    this.eventBus = vertx.eventBus();

                    // 注册 Event Bus 处理器
                    registerEventHandlers();

                    // 启动定时任务：检查超时支付
                    vertx.setPeriodic(60000, id -> checkTimeoutPayments());

                    startServer(config)
                            .compose(server -> {
                                this.registrar = new ServiceRegistrar(vertx, config);
                                int port = server.actualPort();
                                String host = config.getJsonObject("service").getString("host", "localhost");
                                String serviceName = config.getJsonObject("service").getString("name", "payment-service");

                                JsonObject metadata = new JsonObject()
                                        .put("version", "1.0.0")
                                        .put("type", "payment");

                                return registrar.registerHttpService(serviceName, host, port, metadata);
                            })
                            .onSuccess(v -> {
                                logger.info("✅ Payment Service started");
                                startPromise.complete();
                            })
                            .onFailure(startPromise::fail);
                })
                .onFailure(startPromise::fail);
    }

    private void registerEventHandlers() {
        // 监听创建支付请求（来自订单服务）
        eventBus.consumer("payment.create", msg -> {
            JsonObject request = (JsonObject) msg.body();
            String orderId = request.getString("orderId");
            String userId = request.getString("userId");
            double amount = request.getDouble("amount");

            logger.info("Received payment create for order: {}, amount: {}", orderId, amount);

            // 幂等检查
            if (orderPaymentStatus.containsKey(orderId)) {
                PaymentStatus status = orderPaymentStatus.get(orderId);
                if (status == PaymentStatus.SUCCESS) {
                    msg.reply(new JsonObject().put("orderId", orderId).put("error", "Already paid"));
                    return;
                }
                if (status == PaymentStatus.PENDING || status == PaymentStatus.PROCESSING) {
                    msg.reply(new JsonObject().put("orderId", orderId).put("error", "Payment in progress"));
                    return;
                }
            }

            // 创建支付单
            PaymentOrder payment = new PaymentOrder(orderId, userId, amount);
            payments.put(payment.getId(), payment);
            orderPaymentStatus.put(orderId, PaymentStatus.PENDING);

            // 模拟异步支付处理（实际应调用支付宝/微信接口）
            processPaymentAsync(payment);

            msg.reply(new JsonObject()
                    .put("orderId", orderId)
                    .put("payId", payment.getId())
                    .put("status", PaymentStatus.PENDING.name()));
        });

        // 监听取消支付请求（Saga 补偿）
        eventBus.consumer("payment.cancel", msg -> {
            JsonObject request = (JsonObject) msg.body();
            String orderId = request.getString("orderId");

            logger.info("Received payment cancel for order: {}", orderId);

            // 查找并取消支付单
            payments.values().stream()
                    .filter(p -> p.getOrderId().equals(orderId))
                    .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                    .forEach(p -> {
                        p.setStatus(PaymentStatus.CANCELLED);
                        orderPaymentStatus.put(orderId, PaymentStatus.CANCELLED);
                        logger.info("Payment cancelled: {} for order {}", p.getId(), orderId);
                    });

            msg.reply(new JsonObject().put("orderId", orderId).put("cancelled", true));
        });
    }

    /**
     * 模拟异步支付处理
     */
    private void processPaymentAsync(PaymentOrder payment) {
        orderPaymentStatus.put(payment.getOrderId(), PaymentStatus.PROCESSING);

        // 模拟支付网关调用（随机成功/失败）
        vertx.setTimer((long) (5000 + Math.random() * 10000), id -> {
            // 80% 成功率
            boolean success = Math.random() < 0.8;

            if (success) {
                payment.setStatus(PaymentStatus.SUCCESS);
                orderPaymentStatus.put(payment.getOrderId(), PaymentStatus.SUCCESS);

                logger.info("✅ Payment success: {} for order {}", payment.getId(), payment.getOrderId());

                // 通知订单服务支付成功
                eventBus.publish("payment.completed", new JsonObject()
                        .put("orderId", payment.getOrderId())
                        .put("payId", payment.getId())
                        .put("success", true));

                // 通知库存服务扣减库存
                eventBus.send("inventory.deduct", new JsonObject()
                        .put("orderId", payment.getOrderId()));

            } else {
                payment.setStatus(PaymentStatus.FAILED);
                orderPaymentStatus.put(payment.getOrderId(), PaymentStatus.FAILED);

                logger.warn("❌ Payment failed: {} for order {}", payment.getId(), payment.getOrderId());

                // 通知订单服务支付失败（触发 Saga 补偿）
                eventBus.publish("payment.completed", new JsonObject()
                        .put("orderId", payment.getOrderId())
                        .put("success", false)
                        .put("reason", "Bank rejected"));
            }
        });
    }

    /**
     * 检查超时支付（30分钟）
     */
    private void checkTimeoutPayments() {
        long now = System.currentTimeMillis();
        long timeout = 30 * 60 * 1000; // 30分钟

        payments.values().stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .filter(p -> (now - p.getCreatedAt()) > timeout)
                .forEach(p -> {
                    p.setStatus(PaymentStatus.TIMEOUT);
                    orderPaymentStatus.put(p.getOrderId(), PaymentStatus.TIMEOUT);
                    logger.info("Payment timeout: {} for order {}", p.getId(), p.getOrderId());

                    // 触发库存释放
                    eventBus.send("inventory.release", new JsonObject()
                            .put("orderId", p.getOrderId()));
                });
    }

    private Future<HttpServer> startServer(JsonObject config) {
        int port = config.getJsonObject("http").getInteger("port", 8085);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        router.get("/health").handler(this::healthCheck);
        router.get("/api/payments/:payId").handler(this::getPayment);
        router.get("/api/payments/order/:orderId").handler(this::getOrderPayment);
        router.post("/api/payments/:payId/simulate").handler(this::simulatePayment); // 模拟支付回调

        router.errorHandler(500, ctx -> {
            logger.error("Error", ctx.failure());
            ctx.response().setStatusCode(500)
                    .end(ApiResponse.error("Internal error").toJson().encode());
        });

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(port);
    }

    // ========== API Handlers ==========

    private void healthCheck(RoutingContext ctx) {
        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("service", "payment-service")
                        .put("payments", payments.size())).toJson().encode());
    }

    private void getPayment(RoutingContext ctx) {
        String payId = ctx.pathParam("payId");
        PaymentOrder payment = payments.get(payId);

        if (payment == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Payment not found").toJson().encode());
            return;
        }

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(payment.toJson()).toJson().encode());
    }

    private void getOrderPayment(RoutingContext ctx) {
        String orderId = ctx.pathParam("orderId");
        PaymentStatus status = orderPaymentStatus.get(orderId);

        if (status == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("No payment for this order").toJson().encode());
            return;
        }

        // 查找支付单
        PaymentOrder payment = payments.values().stream()
                .filter(p -> p.getOrderId().equals(orderId))
                .findFirst()
                .orElse(null);

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("orderId", orderId)
                        .put("status", status.name())
                        .put("payment", payment != null ? payment.toJson() : null)).toJson().encode());
    }

    /**
     * 模拟支付回调（用于测试）
     */
    private void simulatePayment(RoutingContext ctx) {
        String payId = ctx.pathParam("payId");
        PaymentOrder payment = payments.get(payId);

        if (payment == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Payment not found").toJson().encode());
            return;
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Payment already processed").toJson().encode());
            return;
        }

        // 手动触发支付成功
        payment.setStatus(PaymentStatus.SUCCESS);
        orderPaymentStatus.put(payment.getOrderId(), PaymentStatus.SUCCESS);

        // 通知相关服务
        eventBus.publish("payment.completed", new JsonObject()
                .put("orderId", payment.getOrderId())
                .put("payId", payment.getId())
                .put("success", true));

        eventBus.send("inventory.deduct", new JsonObject()
                .put("orderId", payment.getOrderId()));

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success("Payment simulated success").toJson().encode());
    }

    // ========== 内部类 ==========

    private static class PaymentOrder {
        private final String id;
        private final String orderId;
        private final String userId;
        private final double amount;
        private PaymentStatus status;
        private final long createdAt;
        private Long paidAt;

        PaymentOrder(String orderId, String userId, double amount) {
            this.id = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            this.orderId = orderId;
            this.userId = userId;
            this.amount = amount;
            this.status = PaymentStatus.PENDING;
            this.createdAt = System.currentTimeMillis();
        }

        JsonObject toJson() {
            return new JsonObject()
                    .put("id", id)
                    .put("orderId", orderId)
                    .put("userId", userId)
                    .put("amount", amount)
                    .put("status", status.name())
                    .put("createdAt", createdAt)
                    .put("paidAt", paidAt);
        }

        // Getters & Setters...
        public String getId() { return id; }
        public String getOrderId() { return orderId; }
        public String getUserId() { return userId; }
        public double getAmount() { return amount; }
        public PaymentStatus getStatus() { return status; }
        public void setStatus(PaymentStatus status) {
            this.status = status;
            if (status == PaymentStatus.SUCCESS) {
                this.paidAt = System.currentTimeMillis();
            }
        }
        public long getCreatedAt() { return createdAt; }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (registrar != null) {
            registrar.unregister().onComplete(v -> stopPromise.complete());
        } else {
            stopPromise.complete();
        }
    }
}