package com.ecommerce.common.util;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

public class JwtUtil {

    // 生产环境应从配置文件/环境变量读取，或使用非对称加密
    private static final String SECRET = "your-256-bit-secret-key-change-in-production";
    private static final String ISSUER = "ecommerce-platform";
    private static final long EXPIRATION_HOURS = 24;

    private final JWTAuth jwtAuth;

    public JwtUtil(Vertx vertx) {
        JWTAuthOptions config = new JWTAuthOptions()
                .addPubSecKey(new PubSecKeyOptions()
                        .setAlgorithm("HS256")
                        .setBuffer(SECRET));

        this.jwtAuth = JWTAuth.create(vertx, config);
    }

    /**
     * 生成 JWT Token
     */
    public String generateToken(String userId, String username, String role) {
        JsonObject claims = new JsonObject()
                .put("sub", userId)           // subject: 用户ID
                .put("username", username)
                .put("role", role)
                .put("iss", ISSUER)           // issuer
                .put("iat", System.currentTimeMillis() / 1000)  // issued at
                .put("exp", System.currentTimeMillis() / 1000 + (EXPIRATION_HOURS * 3600)); // expiration

        return jwtAuth.generateToken(claims);
    }

    /**
     * 验证 Token（异步）
     */
    public JWTAuth getAuthProvider() {
        return jwtAuth;
    }
}