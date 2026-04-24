package com.ecommerce.inventory;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.discovery.ServiceRegistrar;
import com.ecommerce.common.model.ApiResponse;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存服务 - Event Sourcing 设计
 *
 * 核心概念：
 * - 不直接修改库存数量，而是记录库存事件（预占、扣减、释放）
 * - 通过重放事件得到当前库存状态
 * - 支持审计追踪和异常恢复
 */
public class InventoryServiceApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(InventoryServiceApplication.class);
    public static final String DEFAULT_REDIS_HOST = "localhost";
    public static final Integer DEFAULT_REDIS_PORT = 6379;
    public static final String DEFAULT_SERVICE_NAME = "inventory-service";
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8084;

    private ServiceRegistrar registrar;
    private EventBus eventBus;
    private RedisAPI redis;

    /**内存中的库存事件存储（实际应使用数据库 + Redis）*/
    private final Map<String, List<InventoryEvent>> eventStore = new ConcurrentHashMap<>();
     /**实时库存缓存*/
    private final Map<String, Integer> stockCache = new ConcurrentHashMap<>();
    /**预占记录：orderId -> [(sku, quantity)]*/
    private final Map<String, List<Reservation>> reservations = new ConcurrentHashMap<>();

    // 库存事件类型
    public enum EventType {
        STOCK_ADDED,      // 入库
        STOCK_RESERVED,   // 预占（订单创建时）
        STOCK_RELEASED,   // 释放（订单取消时）
        STOCK_DEDUCTED,   // 扣减（支付完成时）
        STOCK_RETURNED    // 退货返还
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
                vertx.deployVerticle(new InventoryServiceApplication());
                logger.info("Inventory service started in cluster mode");
            } else {
                logger.error("Failed to start cluster", res.cause());
            }
        });
//        Launcher.executeCommand("run", InventoryServiceApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    this.eventBus = vertx.eventBus();

                    // 初始化 Redis
                    JsonObject redisConfig = config.getJsonObject("database", new JsonObject())
                            .getJsonObject("redis", new JsonObject());
                    RedisOptions redisOptions = new RedisOptions()
                            .setConnectionString("redis://" +
                                    redisConfig.getString("host", DEFAULT_REDIS_HOST) +
                                    ":" +
                                    redisConfig.getInteger("port", DEFAULT_REDIS_PORT));
                    Redis redisClient = Redis.createClient(vertx, redisOptions);
                    this.redis = RedisAPI.api(redisClient);

                    // 初始化测试库存
                    initTestStock();

                    // 注册 Event Bus 处理器
                    registerEventHandlers();

                    startServer(config)
                            .compose(server -> {
                                this.registrar = new ServiceRegistrar(vertx, config);
                                String host = config.getJsonObject("service").getString("host", DEFAULT_HOST);
                                int port = server.actualPort();
                                String serviceName = config.getJsonObject("service").getString("name", DEFAULT_SERVICE_NAME);

                                JsonObject metadata = new JsonObject()
                                        .put("version", "1.0.0")
                                        .put("type", "inventory");

                                return registrar.registerHttpService(serviceName, host, port, metadata);
                            })
                            .onSuccess(v -> {
                                logger.info("✅ Inventory Service started");
                                startPromise.complete();
                            })
                            .onFailure(startPromise::fail);
                })
                .onFailure(startPromise::fail);
    }

    private void initTestStock() {
        // 初始化与商品服务一致的库存
        addStock("SKU001", 100); // iPhone 15 Pro
        addStock("SKU002", 50);  // MacBook Pro
        addStock("SKU003", 200); // AirPods Pro
        addStock("SKU004", 80);  // iPad Air
        addStock("SKU005", 150); // 小米14

        logger.info("Initialized test stock for {} SKUs", stockCache.size());
    }

    private void addStock(String sku, int quantity) {
        stockCache.put(sku, quantity);
        recordEvent(sku, EventType.STOCK_ADDED, quantity, null);
    }

    private void recordEvent(String sku, EventType type, int quantity, String orderId) {
        InventoryEvent event = new InventoryEvent(sku, type, quantity, orderId, System.currentTimeMillis());
        eventStore.computeIfAbsent(sku, k -> new ArrayList<>()).add(event);
        logger.debug("Event recorded: {} {} {} (order: {})", sku, type, quantity, orderId);
    }

    private void registerEventHandlers() {
        // 监听库存预占请求（来自订单服务）
        eventBus.consumer("inventory.reserve", msg -> {
            JsonObject request = (JsonObject) msg.body();
            String orderId = request.getString("orderId");
            JsonArray items = request.getJsonArray("items");

            logger.info("Received reserve request for order: {}", orderId);

            // 尝试预占库存
            boolean success = reserveStock(orderId, items);

            // 回复结果
            msg.reply(new JsonObject()
                    .put("orderId", orderId)
                    .put("success", success));

            if (success) {
                // 发送预占成功事件
                eventBus.publish("inventory.reserved", new JsonObject()
                        .put("orderId", orderId)
                        .put("success", true));
            }
        });

        // 监听库存释放请求（订单取消/失败时）
        eventBus.consumer("inventory.release", msg -> {
            JsonObject request = (JsonObject) msg.body();
            String orderId = request.getString("orderId");

            logger.info("Received release request for order: {}", orderId);

            boolean released = releaseStock(orderId);

            msg.reply(new JsonObject()
                    .put("orderId", orderId)
                    .put("released", released));
        });

        // 监听库存扣减请求（支付完成时）
        eventBus.consumer("inventory.deduct", msg -> {
            JsonObject request = (JsonObject) msg.body();
            String orderId = request.getString("orderId");

            logger.info("Received deduct request for order: {}", orderId);

            boolean success = deductStock(orderId);

            msg.reply(new JsonObject()
                    .put("orderId", orderId)
                    .put("success", success));
        });
    }

    /**
     * 预占库存：检查并锁定
     */
    private boolean reserveStock(String orderId, JsonArray items) {
        // 先检查所有 SKU 库存是否充足
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.getJsonObject(i);
            String sku = item.getString("sku");
            int quantity = item.getInteger("quantity");

            int available = stockCache.getOrDefault(sku, 0);
            if (available < quantity) {
                logger.warn("Insufficient stock for {}: need {}, have {}", sku, quantity, available);
                return false;
            }
        }

        // 库存充足，执行预占
        List<Reservation> reserved = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.getJsonObject(i);
            String sku = item.getString("sku");
            int quantity = item.getInteger("quantity");

            // 扣减可用库存
            stockCache.compute(sku, (k, v) -> v - quantity);
            reserved.add(new Reservation(sku, quantity));

            // 记录事件
            recordEvent(sku, EventType.STOCK_RESERVED, quantity, orderId);

            // 更新 Redis 缓存
            redis.set(List.of("stock:" + sku, String.valueOf(stockCache.get(sku))));
        }

        reservations.put(orderId, reserved);
        logger.info("Stock reserved for order {}: {}", orderId, reserved);
        return true;
    }

    /**
     * 释放库存：回滚预占
     */
    private boolean releaseStock(String orderId) {
        List<Reservation> reserved = reservations.remove(orderId);
        if (reserved == null) {
            logger.warn("No reservation found for order: {}", orderId);
            return false;
        }

        for (Reservation r : reserved) {
            // 恢复库存
            stockCache.compute(r.sku, (k, v) -> v + r.quantity);
            recordEvent(r.sku, EventType.STOCK_RELEASED, r.quantity, orderId);

            // 更新 Redis
            redis.set(List.of("stock:" + r.sku, String.valueOf(stockCache.get(r.sku))));
        }

        logger.info("Stock released for order {}: {}", orderId, reserved);
        return true;
    }

    /**
     * 扣减库存：预占转实际扣减
     */
    private boolean deductStock(String orderId) {
        List<Reservation> reserved = reservations.get(orderId);
        if (reserved == null) {
            logger.warn("No reservation found for order: {}", orderId);
            return false;
        }

        for (Reservation r : reserved) {
            recordEvent(r.sku, EventType.STOCK_DEDUCTED, r.quantity, orderId);
        }

        // 清除预占记录（已转为实际扣减）
        reservations.remove(orderId);
        logger.info("Stock deducted for order {}", orderId);
        return true;
    }

    private Future<HttpServer> startServer(JsonObject config) {
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", DEFAULT_PORT);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        router.get("/health").handler(this::healthCheck);
        router.get("/api/inventory/:sku").handler(this::getStock);
        router.get("/api/inventory/:sku/events").handler(this::getEvents);
        router.get("/api/inventory").handler(this::getAllStock);

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
        JsonObject health = new JsonObject()
                .put("status", "UP")
                .put("service", "inventory-service")
                .put("skus", stockCache.size())
                .put("activeReservations", reservations.size());

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(health).toJson().encode());
    }

    private void getStock(RoutingContext ctx) {
        String sku = ctx.pathParam("sku");
        int stock = stockCache.getOrDefault(sku, 0);
        int reserved = reservations.values().stream()
                .flatMap(List::stream)
                .filter(r -> r.sku.equals(sku))
                .mapToInt(r -> r.quantity)
                .sum();

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("sku", sku)
                        .put("available", stock)
                        .put("reserved", reserved)
                        .put("actual", stock + reserved)).toJson().encode());
    }

    private void getEvents(RoutingContext ctx) {
        String sku = ctx.pathParam("sku");
        List<InventoryEvent> events = eventStore.getOrDefault(sku, new ArrayList<>());

        JsonArray array = new JsonArray();
        events.forEach(e -> array.add(e.toJson()));

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(new JsonObject()
                        .put("sku", sku)
                        .put("events", array)
                        .put("total", events.size())).toJson().encode());
    }

    private void getAllStock(RoutingContext ctx) {
        JsonObject result = new JsonObject();
        stockCache.forEach((sku, quantity) -> {
            int reserved = reservations.values().stream()
                    .flatMap(List::stream)
                    .filter(r -> r.sku.equals(sku))
                    .mapToInt(r -> r.quantity)
                    .sum();

            result.put(sku, new JsonObject()
                    .put("available", quantity)
                    .put("reserved", reserved));
        });

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(result).toJson().encode());
    }

    // ========== 内部类 ==========

    private static class InventoryEvent {
        final String sku;
        final EventType type;
        final int quantity;
        final String orderId;
        final long timestamp;

        InventoryEvent(String sku, EventType type, int quantity, String orderId, long timestamp) {
            this.sku = sku;
            this.type = type;
            this.quantity = quantity;
            this.orderId = orderId;
            this.timestamp = timestamp;
        }

        JsonObject toJson() {
            return new JsonObject()
                    .put("sku", sku)
                    .put("type", type.name())
                    .put("quantity", quantity)
                    .put("orderId", orderId)
                    .put("timestamp", timestamp);
        }
    }

    private static class Reservation {
        final String sku;
        final int quantity;

        Reservation(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }

        @Override
        public String toString() {
            return sku + ":" + quantity;
        }
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