package com.ecommerce.common.model;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.UUID;

public class User {

    private String id;
    private String username;
    private String email;
    private String passwordHash;  // 存储加密后的密码
    private String role;          // USER, ADMIN
    private Instant createdAt;
    private boolean enabled;

    public User() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.role = "USER";
        this.enabled = true;
    }

    public User(String username, String email, String passwordHash) {
        this();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    // 转换为 JSON（返回给客户端时隐藏密码）
    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("username", username)
                .put("email", email)
                .put("role", role)
                .put("createdAt", createdAt.toString())
                .put("enabled", enabled);
    }

    public JsonObject toJsonWithPassword() {
        return toJson().put("passwordHash", passwordHash);
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}