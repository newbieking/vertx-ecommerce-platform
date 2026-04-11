package com.ecommerce.common.util;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

public class JwtUtil {

    private final JWTAuth jwtAuth;

    private final JwtUtilOptions options;

    public record JwtUtilOptions(String secret, String issuer, long expirationHours) {
        public static JwtUtilOptions fromJson(JsonObject json) {
            return new JwtUtilOptions(json.getString("secret"), json.getString("issuer"), json.getLong("expirationHours"));
        }
    }

    public JwtUtil(Vertx vertx, JwtUtilOptions options) {
        JWTAuthOptions config = new JWTAuthOptions()
                .addPubSecKey(new PubSecKeyOptions()
                        .setAlgorithm("HS256")
                        .setBuffer(options.secret));

        this.jwtAuth = JWTAuth.create(vertx, config);
        this.options = options;
    }

    /**
     * 生成 JWT Token
     */
    public String generateToken(String userId, String username, String role) {
        JsonObject claims = new JsonObject()
                .put("sub", userId)           // subject: 用户ID
                .put("username", username)
                .put("role", role)
                .put("iss", this.options.issuer)           // issuer
                .put("iat", System.currentTimeMillis() / 1000)  // issued at
                .put("exp", System.currentTimeMillis() / 1000 + (this.options.expirationHours * 3600)); // expiration

        return jwtAuth.generateToken(claims);
    }

    /**
     * 验证 Token（异步）
     */
    public JWTAuth getAuthProvider() {
        return jwtAuth;
    }
}