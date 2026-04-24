package com.ecommerce.order;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.discovery.ServiceRegistrar;
import com.ecommerce.common.model.ApiResponse;
import com.ecommerce.common.model.Order;
import com.ecommerce.common.model.OrderItem;
import com.ecommerce.order.saga.OrderSaga;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OrderServiceApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceApplication.class);

    private ServiceRegistrar registrar;
    private WebClient webClient;
    private EventBus eventBus;

    // 模拟订单存储（后续替换为 MySQL）
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

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
                vertx.deployVerticle(new OrderServiceApplication());
                logger.info("Order service started in cluster mode");
            } else {
                logger.error("Failed to start cluster", res.cause());
            }
        });
//        Launcher.executeCommand("run", OrderServiceApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    this.webClient = WebClient.create(vertx);
                    this.eventBus = vertx.eventBus();

                    // 注册 Event Bus 消费者
                    registerEventBusConsumers();

                    startServer(config)
                            .compose(server -> {
                                this.registrar = new ServiceRegistrar(vertx, config);
                                int port = server.actualPort();
                                String host = config.getString("service.host", "localhost");

                                JsonObject metadata = new JsonObject()
                                        .put("version", "1.0.0")
                                        .put("type", "order");

                                return registrar.registerHttpService("order-service", host, port, metadata);
                            })
                            .onSuccess(v -> {
                                logger.info("✅ Order Service started");
                                startPromise.complete();
                            })
                            .onFailure(startPromise::fail);
                })
                .onFailure(startPromise::fail);
    }

    private void registerEventBusConsumers() {
        // 监听库存扣减结果
        eventBus.consumer("inventory.reserved", msg -> {
            JsonObject result = (JsonObject) msg.body();
            String orderId = result.getString("orderId");
            boolean success = result.getBoolean("success");

            logger.info("Inventory reserved result for {}: {}", orderId, success);

            Order order = orders.get(orderId);
            if (order != null && success) {
                order.setStatus(Order.Status.STOCK_RESERVED);
                // 触发支付
                eventBus.send("payment.create", new JsonObject()
                        .put("orderId", orderId)
                        .put("amount", order.getTotalAmount())
                        .put("userId", order.getUserId()));
            } else {
                // 补偿：释放库存
                order.setStatus(Order.Status.CANCELLED);
                order.setCancelReason("库存不足");
            }
        });

        // 监听支付结果
        eventBus.consumer("payment.completed", msg -> {
            JsonObject result = (JsonObject) msg.body();
            String orderId = result.getString("orderId");
            boolean success = result.getBoolean("success");

            Order order = orders.get(orderId);
            if (order != null && success) {
                order.setStatus(Order.Status.PAID);
                order.setPayId(result.getString("payId"));
                logger.info("Order {} paid successfully", orderId);
            }
        });
    }

    private Future<HttpServer> startServer(JsonObject config) {
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", 8083);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        router.get("/health").handler(this::healthCheck);

        // 订单接口
        router.post("/api/orders").handler(this::createOrder);
        router.get("/api/orders/:id").handler(this::getOrder);
        router.get("/api/orders/user/:userId").handler(this::getUserOrders);
        router.post("/api/orders/:id/cancel").handler(this::cancelOrder);

        // 状态机演示：手动推进状态（实际由事件驱动）
        router.post("/api/orders/:id/pay").handler(this::simulatePay);

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
                        .put("service", "order-service")
                        .put("orders", orders.size())).toJson().encode());
    }

    /**
     * 创建订单（核心：Saga 分布式事务）
     * POST /api/orders
     * Body: {
     * "userId": "user-123",
     * "items": [
     * {"sku": "SKU001", "quantity": 2},
     * {"sku": "SKU002", "quantity": 1}
     * ]
     * }
     */
    private void createOrder(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        String userId = body.getString("userId");
        JsonArray itemsJson = body.getJsonArray("items");

        if (userId == null || itemsJson == null || itemsJson.isEmpty()) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("userId and items required").toJson().encode());
            return;
        }

        // 构建订单
        Order order = new Order();
        order.setUserId(userId);

        List<Future<JsonObject>> futures = new ArrayList<>();

        // 查询商品信息（并行）
        for (int i = 0; i < itemsJson.size(); i++) {
            JsonObject item = itemsJson.getJsonObject(i);
            String sku = item.getString("sku");
            int quantity = item.getInteger("quantity", 1);

            Future<JsonObject> f = queryProduct(sku)
                    .map(product -> {
                        if (product == null) {
                            throw new RuntimeException("Product not found: " + sku);
                        }
                        // 添加订单项
                        order.getItems().add(new OrderItem(
                                sku,
                                product.getString("name"),
                                new BigDecimal(product.getString("price")),
                                quantity
                        ));
                        return product;
                    });

            futures.add(f);
        }

        Future.all(futures)
                .compose(v -> {
                    // 计算总价
                    order.calculateTotal();

                    // 保存订单
                    orders.put(order.getId(), order);
                    logger.info("Order created: {} for user {}, amount: {}",
                            order.getId(), userId, order.getTotalAmount());

                    // 执行 Saga 分布式事务
                    return executeOrderSaga(order);
                })
                .onSuccess(result -> {
                    ctx.response().setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.success(new JsonObject()
                                    .put("orderId", order.getId())
                                    .put("status", order.getStatus().name())
                                    .put("totalAmount", order.getTotalAmount())
                                    .put("message", "Order created, processing...")).toJson().encode());
                })
                .onFailure(err -> {
                    // Saga 失败，订单标记为取消
                    order.setStatus(Order.Status.CANCELLED);
                    order.setCancelReason(err.getMessage());

                    ctx.response().setStatusCode(400)
                            .end(ApiResponse.error("Order failed: " + err.getMessage()).toJson().encode());
                });
    }

    /**
     * 执行订单 Saga
     */
    private Future<JsonObject> executeOrderSaga(Order order) {
        JsonObject context = new JsonObject()
                .put("orderId", order.getId())
                .put("userId", order.getUserId())
                .put("items", new JsonArray(
                        order.getItems().stream()
                                .map(item -> new JsonObject()
                                        .put("sku", item.getSku())
                                        .put("quantity", item.getQuantity()))
                                .toList()))
                .put("amount", order.getTotalAmount());

        OrderSaga saga = new OrderSaga(vertx);

        // Step 1: 校验商品（本地，无需补偿）
        saga.addStep("validate-products", ctx -> {
            return Future.succeededFuture(new JsonObject().put("validated", true));
        }, null);

        // Step 2: 预占库存（远程，需补偿：释放库存）
        saga.addStep("reserve-inventory", ctx -> {
            Promise<JsonObject> promise = Promise.promise();

            // 通过 Event Bus 发送库存预占请求
            eventBus.request("inventory.reserve", ctx, reply -> {
                if (reply.succeeded()) {
                    JsonObject result = (JsonObject) reply.result().body();
                    if (result.getBoolean("success")) {
                        order.setStatus(Order.Status.STOCK_RESERVED);
                        promise.complete(new JsonObject().put("inventoryReserved", true));
                    } else {
                        promise.fail("Inventory reservation failed");
                    }
                } else {
                    promise.fail(reply.cause());
                }
            });

            return promise.future();
        }, ctx -> {
            // 补偿：释放库存
            logger.info("Compensating: release inventory for {}", ctx.getString("orderId"));
            eventBus.send("inventory.release", ctx);
            return Future.succeededFuture(ctx);
        });

        // Step 3: 创建支付单（远程，需补偿：取消支付）
        saga.addStep("create-payment", ctx -> {
            Promise<JsonObject> promise = Promise.promise();

            eventBus.request("payment.create", ctx, reply -> {
                if (reply.succeeded()) {
                    JsonObject result = (JsonObject) reply.result().body();
                    if (!result.containsKey("error")) {
                        order.setStatus(Order.Status.PAID); // 支付服务异步处理，这里标记为处理中
                        promise.complete(new JsonObject()
                                .put("payId", result.getString("payId"))
                                .put("paymentCreated", true));
                    } else {
                        promise.fail(result.getString("error"));
                    }
                } else {
                    promise.fail(reply.cause());
                }
            });

            return promise.future();
        }, ctx -> {
            // 补偿：取消支付
            logger.info("Compensating: cancel payment for {}", ctx.getString("orderId"));
            eventBus.send("payment.cancel", ctx);
            return Future.succeededFuture(ctx);
        });

        return saga.execute(context);
    }

    /**
     * 查询商品信息（通过 HTTP 调用商品服务）
     */
    private Future<JsonObject> queryProduct(String sku) {
        // 实际应通过 Service Discovery 动态发现
        return webClient.get(8082, "localhost", "/api/products/" + sku)
                .send()
                .map(resp -> {
                    if (resp.statusCode() == 200) {
                        JsonObject result = resp.bodyAsJsonObject();
                        if (result.getInteger("code") == 200) {
                            return result.getJsonObject("data");
                        }
                    }
                    return null;
                });
    }

    private void getOrder(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        Order order = orders.get(id);

        if (order == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Order not found").toJson().encode());
            return;
        }

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(order.toJson()).toJson().encode());
    }

    private void getUserOrders(RoutingContext ctx) {
        String userId = ctx.pathParam("userId");

        List<JsonObject> userOrders = orders.values().stream()
                .filter(o -> o.getUserId().equals(userId))
                .map(Order::toJson)
                .toList();

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("orders", new JsonArray(userOrders))
                        .put("total", userOrders.size())).toJson().encode());
    }

    private void cancelOrder(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        Order order = orders.get(id);

        if (order == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Order not found").toJson().encode());
            return;
        }

        if (order.getStatus() == Order.Status.PAID || order.getStatus() == Order.Status.SHIPPED) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Cannot cancel paid/shipped order").toJson().encode());
            return;
        }

        order.setStatus(Order.Status.CANCELLED);
        order.setCancelReason("User cancelled");

        // 触发补偿：释放库存
        eventBus.send("inventory.release", new JsonObject()
                .put("orderId", id)
                .put("items", new JsonArray(
                        order.getItems().stream()
                                .map(item -> new JsonObject()
                                        .put("sku", item.getSku())
                                        .put("quantity", item.getQuantity()))
                                .toList())));

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success("Order cancelled").toJson().encode());
    }

    private void simulatePay(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        Order order = orders.get(id);

        if (order == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Order not found").toJson().encode());
            return;
        }

        // 模拟支付成功回调
        order.setStatus(Order.Status.PAID);
        order.setPayId("PAY-" + System.currentTimeMillis());

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("orderId", id)
                        .put("status", "PAID")).toJson().encode());
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