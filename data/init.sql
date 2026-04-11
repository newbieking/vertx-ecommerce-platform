CREATE DATABASE IF NOT EXISTS ecommerce_products CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ecommerce_products;

CREATE TABLE IF NOT EXISTS products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(50) UNIQUE NOT NULL COMMENT '商品编码',
    `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
    description TEXT COMMENT '商品描述',
    price DECIMAL(10, 2) NOT NULL COMMENT '售价',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存',
    category VARCHAR(50) COMMENT '分类',
    status TINYINT DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_status (status)
) ENGINE=InnoDB;

-- 插入测试数据
INSERT INTO products (sku, name, description, price, stock, category) VALUES
 ('SKU001', 'iPhone 15 Pro', '苹果最新旗舰手机', 7999.00, 100, '手机数码'),
 ('SKU002', 'MacBook Pro 14', '专业级笔记本电脑', 14999.00, 50, '电脑办公'),
 ('SKU003', 'AirPods Pro 2', '主动降噪耳机', 1899.00, 200, '手机数码'),
 ('SKU004', 'iPad Air 5', '轻薄平板电脑', 4799.00, 80, '电脑办公'),
 ('SKU005', '小米14', '徕卡影像旗舰', 3999.00, 150, '手机数码');