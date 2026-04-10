package com.ecommerce.user;

import com.ecommerce.common.config.ConfigLoader;
import com.ecommerce.common.model.ApiResponse;
import com.ecommerce.common.model.User;
import com.ecommerce.common.util.JwtUtil;
import com.ecommerce.common.util.PasswordUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserServiceApplication extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(UserServiceApplication.class);

    // Mock 数据存储（后续替换为 MongoDB）
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();

    private JwtUtil jwtUtil;
    private JWTAuth jwtAuth;

    public static void main(String[] args) {
        Launcher.executeCommand("run", UserServiceApplication.class.getName());
    }

    @Override
    public void start(Promise<Void> startPromise) {
        ConfigLoader.load(vertx)
                .onSuccess(config -> {
                    jwtUtil = new JwtUtil(vertx);
                    jwtAuth = jwtUtil.getAuthProvider();
                    startServer(config, startPromise);
                })
                .onFailure(startPromise::fail);
    }

    private void startServer(JsonObject config, Promise<Void> startPromise) {
        int port = config.getJsonObject("http", new JsonObject())
                .getInteger("port", 8081);

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // ========== 公开接口（无需认证）==========
        router.get("/health").handler(this::healthCheck);
        router.post("/api/users/register").handler(this::register);
        router.post("/api/users/login").handler(this::login);

        // ========== 需要认证的接口 ==========
        // 使用 JWTAuthHandler 拦截
        router.route("/api/users/me").handler(JWTAuthHandler.create(jwtAuth));
        router.get("/api/users/me").handler(this::getCurrentUser);

        router.route("/api/users/*").handler(JWTAuthHandler.create(jwtAuth));
        router.get("/api/users").handler(this::getAllUsers);
        router.get("/api/users/:id").handler(this::getUserById);

        // 错误处理
        router.route().handler(ctx -> {
            ctx.response()
                    .setStatusCode(404)
                    .putHeader("content-type", "application/json")
                    .end(ApiResponse.error("Not Found").toJson().encode());
        });

        // 全局错误处理器
        router.errorHandler(500, ctx -> {
            logger.error("Internal error", ctx.failure());
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(ApiResponse.error("Internal Server Error").toJson().encode());
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(s -> {
                    logger.info("✅ User Service started on port {}", s.actualPort());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    // ========== 处理器方法 ==========

    private void healthCheck(RoutingContext ctx) {
        ctx.response()
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                        .put("status", "UP")
                        .put("service", "user-service")
                        .put("users", usersById.size())
                        .encode());
    }

    /**
     * 用户注册
     * POST /api/users/register
     * Body: {"username": "alice", "email": "alice@example.com", "password": "123456"}
     */
    private void register(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();

        if (body == null) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Request body required").toJson().encode());
            return;
        }

        String username = body.getString("username");
        String email = body.getString("email");
        String password = body.getString("password");

        // 校验
        if (username == null || username.length() < 3) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Username must be at least 3 characters").toJson().encode());
            return;
        }
        if (email == null || !email.contains("@")) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Valid email required").toJson().encode());
            return;
        }
        if (password == null || password.length() < 6) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Password must be at least 6 characters").toJson().encode());
            return;
        }

        // 检查重复
        if (usersByUsername.containsKey(username)) {
            ctx.response().setStatusCode(409)
                    .end(ApiResponse.error("Username already exists").toJson().encode());
            return;
        }
        if (usersByEmail.containsKey(email)) {
            ctx.response().setStatusCode(409)
                    .end(ApiResponse.error("Email already registered").toJson().encode());
            return;
        }

        // 创建用户
        String passwordHash = PasswordUtil.hash(password);
        User user = new User(username, email, passwordHash);

        usersById.put(user.getId(), user);
        usersByUsername.put(username, user);
        usersByEmail.put(email, user);

        logger.info("User registered: {} ({})", username, user.getId());

        // 生成 Token
        String token = jwtUtil.generateToken(user.getId(), username, user.getRole());

        JsonObject response = new JsonObject()
                .put("user", user.toJson())
                .put("token", token);

        ctx.response().setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(response).toJson().encode());
    }

    /**
     * 用户登录
     * POST /api/users/login
     * Body: {"email": "alice@example.com", "password": "123456"}
     */
    private void login(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();

        if (body == null) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Request body required").toJson().encode());
            return;
        }

        String email = body.getString("email");
        String password = body.getString("password");

        if (email == null || password == null) {
            ctx.response().setStatusCode(400)
                    .end(ApiResponse.error("Email and password required").toJson().encode());
            return;
        }

        User user = usersByEmail.get(email);
        if (user == null || !user.isEnabled()) {
            ctx.response().setStatusCode(401)
                    .end(ApiResponse.error(401, "Invalid credentials").toJson().encode());
            return;
        }

        // 验证密码
        if (!PasswordUtil.verify(password, user.getPasswordHash())) {
            ctx.response().setStatusCode(401)
                    .end(ApiResponse.error(401, "Invalid credentials").toJson().encode());
            return;
        }

        // 生成新 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        JsonObject response = new JsonObject()
                .put("user", user.toJson())
                .put("token", token);

        logger.info("User logged in: {} ({})", user.getUsername(), user.getId());

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(response).toJson().encode());
    }

    /**
     * 获取当前登录用户信息（需要 JWT）
     * GET /api/users/me
     * Header: Authorization: Bearer <token>
     */
    private void getCurrentUser(RoutingContext ctx) {
        io.vertx.ext.auth.User user = ctx.user();
        if (user == null) {
            ctx.response().setStatusCode(401)
                    .end(ApiResponse.error(401, "Unauthorized").toJson().encode());
            return;
        }

        String userId = user.get("sub");
        User currentUser = usersById.get(userId);

        if (currentUser == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("User not found").toJson().encode());
            return;
        }

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(currentUser.toJson()).toJson().encode());
    }

    /**
     * 获取所有用户（需要 JWT，仅 ADMIN 可访问）
     */
    private void getAllUsers(RoutingContext ctx) {
        // 简单权限检查
        io.vertx.ext.auth.User user = ctx.user();
        String role = user.get("role");

        if (!"ADMIN".equals(role)) {
            ctx.response().setStatusCode(403)
                    .end(ApiResponse.error(403, "Forbidden: Admin only").toJson().encode());
            return;
        }

        JsonObject response = new JsonObject()
                .put("users", usersById.values().stream()
                        .map(User::toJson)
                        .collect(java.util.stream.Collectors.toList()));

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(response).toJson().encode());
    }

    /**
     * 根据 ID 获取用户（需要 JWT）
     */
    private void getUserById(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        User user = usersById.get(id);

        if (user == null) {
            ctx.response().setStatusCode(404)
                    .end(ApiResponse.error("User not found").toJson().encode());
            return;
        }

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(ApiResponse.success(user.toJson()).toJson().encode());
    }
}