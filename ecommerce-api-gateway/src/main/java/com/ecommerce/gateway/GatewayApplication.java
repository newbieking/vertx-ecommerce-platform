package com.ecommerce.gateway;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.model.ApiResponse;
import com.ecommerce.common.util.JwtUtil;
import com.ecommerce.gateway.discovery.ServiceResolver;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class GatewayApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(GatewayApplication.class);

    private JWTAuth jwtAuth;
    private final List<RouteConfig> routes = new ArrayList<>();

    // 无需认证的路径
    private final List<Pattern> publicPaths = new ArrayList<>();

    private ServiceResolver serviceResolver;

    // 全局单例 HttpClient：复用连接池，避免资源泄漏
    private HttpClient httpClient;
    // 熔断器缓存：每个服务实例一个熔断器，线程安全
    private final Map<String, CircuitBreaker> breakerCache = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Launcher.executeCommand("run", GatewayApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    initAuth(config);
                    this.serviceResolver = new ServiceResolver(vertx, config);
                    // 全局HttpClient配置：连接池、最大并发、超时等
                    this.httpClient = vertx.createHttpClient(new HttpClientOptions()
                            .setMaxPoolSize(200)
                            .setConnectTimeout(3000)
                            .setTcpKeepAlive(true)
                    );
                    initRoutes(config);
                    startServer(config, startPromise);
                })
                .onFailure(startPromise::fail);
    }

    private void initAuth(JsonObject config) {
        // 初始化 JWT
        JsonObject jwt = config.getJsonObject("jwt");
        JwtUtil.JwtUtilOptions jwtUtilOptions = JwtUtil.JwtUtilOptions.fromJson(jwt);
        JwtUtil jwtUtil = new JwtUtil(vertx, jwtUtilOptions);
        this.jwtAuth = jwtUtil.getAuthProvider();

        logger.info("JWT authentication initialized");
    }

    private void initRoutes(JsonObject config) {
        JsonArray routesConfig = config.getJsonArray("routes", new JsonArray());

        for (int i = 0; i < routesConfig.size(); i++) {
            JsonObject route = routesConfig.getJsonObject(i);
            RouteConfig rc = new RouteConfig(
                    route.getString("path"),
                    route.getString("target"),
                    route.getBoolean("stripPrefix", false),
                    route.getBoolean("auth", true)
            );
            routes.add(rc);

            // 无需认证的路径加入白名单
            if (!rc.auth) {
                publicPaths.add(Pattern.compile(rc.path.replace("*", ".*")));
                logger.info("Public route: {} -> {}", rc.path, rc.target);
            } else {
                logger.info("Protected route: {} -> {}", rc.path, rc.target);
            }
        }
    }

    private void startServer(JsonObject config, Promise<Void> startPromise) {
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", 8080);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // 健康检查
        router.get("/health").handler(this::healthCheck);

        // 网关层认证中间件
        router.route("/api/*").handler(this::authMiddleware);

        // 动态路由转发
        router.route("/api/*").handler((ctx) -> {
            this.proxyRequest(ctx, config);
        });

        // 错误处理
        router.errorHandler(500, ctx -> {
            logger.error("Gateway error", ctx.failure());
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(ApiResponse.error("Gateway Error").toJson().encode());
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(s -> {
                    logger.info("✅ API Gateway started on port {}", s.actualPort());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    /**
     * 认证中间件：验证 JWT Token
     */
    private void authMiddleware(RoutingContext ctx) {
        String path = ctx.request().path();

        // 检查是否是公开路径
        boolean isPublic = publicPaths.stream()
                .anyMatch(p -> p.matcher(path).matches());

        if (isPublic) {
            logger.debug("Public path, skip auth: {}", path);
            ctx.next();
            return;
        }

        // 获取 Authorization Header
        String authHeader = ctx.request().getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.response()
                    .setStatusCode(401)
                    .putHeader("content-type", "application/json")
                    .end(ApiResponse.error(401, "Missing or invalid Authorization header").toJson().encode());
            return;
        }

        String token = authHeader.substring(7);

        // 验证 JWT
        jwtAuth.authenticate(new JsonObject().put("token", token))
                .onSuccess(user -> {
                    // 将用户信息存入上下文，转发时传递给下游
                    ctx.put("userId", user.get("sub"));
                    ctx.put("username", user.get("username"));
                    ctx.put("role", user.get("role"));
                    logger.debug("Authenticated user: {}", (Object) user.get("username"));
                    ctx.next();
                })
                .onFailure(err -> {
                    logger.warn("Authentication failed: {}", err.getMessage());
                    ctx.response()
                            .setStatusCode(401)
                            .putHeader("content-type", "application/json")
                            .end(ApiResponse.error(401, "Invalid or expired token").toJson().encode());
                });
    }

    /**
     * 代理请求到下游服务
     */
    private void proxyRequest(RoutingContext ctx, JsonObject config) {
        String path = ctx.request().path();
        // 从路径解析服务名：/api/users/me -> user-service
        String serviceName = extractServiceName(path);


        if (serviceName == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("Unknown service for path: " + path).toJson().encode());
            return;
        }


        // 解析服务实例
        serviceResolver.resolve(serviceName)
                .onSuccess(instance -> {
                    String targetUrl = instance.getUrl() + path;
                    logger.info("Proxy: {} {} -> {}", ctx.request().method(), path, targetUrl);

                    // 发送请求（带熔断保护）
                    sendWithCircuitBreaker(ctx, targetUrl, instance, config);
                })
                .onFailure(err -> {
                    logger.error("Service resolution failed: {}", err.getMessage());
                    ctx.response().setStatusCode(503)
                            .end(ApiResponse.error(503, "Service unavailable").toJson().encode());
                });
    }

    private void sendWithCircuitBreaker(RoutingContext ctx, String targetUrl, ServiceResolver.ServiceInstance instance, JsonObject config) {

        // 1. 从配置中心加载熔断配置
        CircuitBreakerOptions breakerOptions = new CircuitBreakerOptions()
                .setMaxFailures(3)      // 3次失败后开启
                .setTimeout(5000)       // 5秒超时
                .setResetTimeout(10000); // 10秒后尝试恢复
        String breakerKey = "cb-" + instance.host + ":" + instance.port;

        // 2. 获取/创建单例熔断器（每个服务一个）
        CircuitBreaker breaker = breakerCache.computeIfAbsent(breakerKey,
                key -> CircuitBreaker.create(key, vertx, breakerOptions)
                        .closeHandler(v -> logger.info("熔断器关闭：{}", key))
                        .openHandler(v -> logger.warn("熔断器开启：{}", key))
                        .halfOpenHandler(v -> logger.info("熔断器半开：{}", key))
        );


        breaker.<JsonObject>execute(promise -> {
                    // 实际 HTTP 请求
                    RequestOptions options = new RequestOptions()
                            .setTimeout(config.getJsonObject("proxy").getLong("timeout", 5000L))
                            .setMethod(ctx.request().method())
                            .setAbsoluteURI(targetUrl);
                    this.httpClient.request(options)
                            .compose(req -> {
                                // 复制头信息
                                ctx.request().headers().forEach(h -> {
                                    if (!h.getKey().equalsIgnoreCase("Host")) {
                                        req.putHeader(h.getKey(), h.getValue());
                                    }
                                });
                                if (ctx.get("userId") != null) {
                                    req.putHeader("X-User-Id", (String) ctx.get("userId"));
                                }
                                return req.send(ctx.body().buffer() != null ? ctx.body().buffer() : Buffer.buffer());
                            })
                            .compose(resp -> {
                                        // 复制头信息
                                        resp.headers().forEach(h -> {
                                            if (!h.getKey().equalsIgnoreCase("Host")) {
                                                ctx.response().putHeader(h.getKey(), h.getValue());
                                            }
                                        });
                                        return resp.body().map(buffer ->
                                                new JsonObject().put("status", resp.statusCode()).put("body", buffer));
                                    }
                            )
                            .onSuccess(promise::complete)
                            .onFailure(promise::fail);
                })
                .onSuccess(result -> ctx.response()
                        .setStatusCode(result.getInteger("status"))
                        .end(result.getBuffer("body")))
                .onFailure(err -> {
                    logger.error("Request failed or circuit breaker open: {}", err.getMessage());
                    ctx.response().setStatusCode(503)
                            .end(ApiResponse.error(503, "Service temporarily unavailable").toJson().encode());
                });

    }

    private String extractServiceName(String path) {
        // TODO 简单映射：/api/users/* -> user-service
        if (path.startsWith("/api/users")) return "user-service";
        if (path.startsWith("/api/products")) return "product-service";
        if (path.startsWith("/api/orders")) return "order-service";
        return null;
    }

    private void healthCheck(RoutingContext ctx) {
        JsonObject health = new JsonObject()
                .put("status", "UP")
                .put("service", "api-gateway")
                .put("routes", routes.size());

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(health).toJson().encode());
    }

    // 路由配置内部类
    private static class RouteConfig {
        final String path;
        final String target;
        final boolean stripPrefix;
        final boolean auth;

        RouteConfig(String path, String target, boolean stripPrefix, boolean auth) {
            this.path = path;
            this.target = target;
            this.stripPrefix = stripPrefix;
            this.auth = auth;
        }
    }
}