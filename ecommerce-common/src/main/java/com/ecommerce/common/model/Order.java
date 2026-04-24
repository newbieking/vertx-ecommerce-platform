package com.ecommerce.common.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Order {

    // 订单状态机
    public enum Status {
        CREATED,           // 已创建
        PRODUCT_CHECKED,   // 商品校验通过
        STOCK_RESERVED,    // 库存预占成功
        PAID,              // 已支付
        SHIPPED,           // 已发货
        COMPLETED,         // 已完成
        CANCELLED,         // 已取消
        STOCK_RELEASED     // 库存已释放
    }

    private String id;
    private String userId;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private Status status;
    private String payId;          // 支付流水号
    private String shipId;         // 物流单号
    private String cancelReason;   // 取消原因
    private Instant createdAt;
    private Instant updatedAt;

    public Order() {
        this.id = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        this.items = new ArrayList<>();
        this.status = Status.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public JsonObject toJson() {
        JsonArray itemsJson = new JsonArray();
        items.forEach(item -> itemsJson.add(item.toJson()));

        return new JsonObject()
                .put("id", id)
                .put("userId", userId)
                .put("items", itemsJson)
                .put("totalAmount", totalAmount)
                .put("status", status.name())
                .put("payId", payId)
                .put("shipId", shipId)
                .put("cancelReason", cancelReason)
                .put("createdAt", createdAt.toString())
                .put("updatedAt", updatedAt.toString());
    }

    public void calculateTotal() {
        this.totalAmount = items.stream()
                .map(item -> item.getPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Getters & Setters...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getPayId() { return payId; }
    public void setPayId(String payId) { this.payId = payId; }
    public String getShipId() { return shipId; }
    public void setShipId(String shipId) { this.shipId = shipId; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}