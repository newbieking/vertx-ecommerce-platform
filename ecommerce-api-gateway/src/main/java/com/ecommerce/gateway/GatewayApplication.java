package com.ecommerce.gateway;

import com.ecommerce.common.config.ConfigLoader;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(GatewayApplication.class);

    public static void main(String[] args) {
        Launcher.executeCommand("run", GatewayApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        // 先加载配置
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    logger.debug("Loaded config: {}", config.encodePrettily());
                    startServer(config, startPromise);
                })
                .onFailure(startPromise::fail);
    }

    private void startServer(JsonObject config, Promise<Void> startPromise) {
        // 从配置读取，默认 8080
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", 8080);
        String serviceName = config.getJsonObject("service", new JsonObject())
                .getString("name", "api-gateway");

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());

        // 健康检查 - 返回配置信息
        router.get("/health").handler(ctx -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("service", serviceName)
                    .put("port", port);
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(health.encode());
        });

        router.get("/api/hello").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end("{\"message\":\"Hello from " + serviceName + "\"}");
        });

        // 配置端点 - 查看当前配置（生产环境应禁用）
        router.get("/api/config").handler(ctx -> {
            // 移除敏感信息
            JsonObject safeConfig = config.copy();
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(safeConfig.encodePrettily());
        });

        router.route().handler(ctx -> {
            ctx.response()
                    .setStatusCode(404)
                    .putHeader("content-type", "application/json")
                    .end("{\"error\":\"Not Found\"}");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(s -> {
                    logger.info("✅ {} started on port {}", serviceName, s.actualPort());
                    startPromise.complete();
                })
                .onFailure(err -> {
                    logger.error("❌ Failed to start server", err);
                    startPromise.fail(err);
                });
    }
}