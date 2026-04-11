package com.ecommerce.common.model;

import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.Instant;

public class Product {

    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private String category;
    private Integer status; // 0-下架 1-上架
    private Instant createdAt;
    private Instant updatedAt;

    public Product() {}

    // 从数据库行转换
    public static Product fromRow(io.vertx.sqlclient.Row row) {
        Product p = new Product();
        p.setId(row.getLong("id"));
        p.setSku(row.getString("sku"));
        p.setName(row.getString("name"));
        p.setDescription(row.getString("description"));
        p.setPrice(row.getBigDecimal("price"));
        p.setStock(row.getInteger("stock"));
        p.setCategory(row.getString("category"));
        p.setStatus(row.getInteger("status"));
        p.setCreatedAt(row.getLocalDateTime("created_at").toInstant(java.time.ZoneOffset.UTC));
        p.setUpdatedAt(row.getLocalDateTime("updated_at").toInstant(java.time.ZoneOffset.UTC));
        return p;
    }

    // 从Json转换（用于缓存反序列化等场景）
    public static Product fromJson(JsonObject json) {
        if (json == null) {
            return null;
        }
        Product p = new Product();
        p.setId(json.getLong("id"));
        p.setSku(json.getString("sku"));
        p.setName(json.getString("name"));
        p.setDescription(json.getString("description"));
        p.setPrice(new BigDecimal(json.getString("price")));
        p.setStock(json.getInteger("stock"));
        p.setCategory(json.getString("category"));
        p.setStatus(json.getInteger("status"));

        // Instant 字段：json中存储的是字符串格式，需要解析
        String createdAtStr = json.getString("createdAt");
        String updatedAtStr = json.getString("updatedAt");
        if (createdAtStr != null) {
            p.setCreatedAt(Instant.parse(createdAtStr));
        }
        if (updatedAtStr != null) {
            p.setUpdatedAt(Instant.parse(updatedAtStr));
        }
        return p;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("sku", sku)
                .put("name", name)
                .put("description", description)
                .put("price", price)
                .put("stock", stock)
                .put("category", category)
                .put("status", status)
                .put("createdAt", createdAt.toString())
                .put("updatedAt", updatedAt.toString());
    }

    // 生成缓存Key
    public String getCacheKey() {
        return "product:" + sku;
    }

    // Getters & Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}