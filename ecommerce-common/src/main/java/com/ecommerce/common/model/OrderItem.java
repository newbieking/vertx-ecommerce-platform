package com.ecommerce.common.model;

import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;

public class OrderItem {

    private String sku;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal subtotal;

    public OrderItem() {}

    public OrderItem(String sku, String productName, BigDecimal price, Integer quantity) {
        this.sku = sku;
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
        this.subtotal = price.multiply(new BigDecimal(quantity));
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("sku", sku)
                .put("productName", productName)
                .put("price", price)
                .put("quantity", quantity)
                .put("subtotal", subtotal);
    }

    // Getters & Setters...
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}