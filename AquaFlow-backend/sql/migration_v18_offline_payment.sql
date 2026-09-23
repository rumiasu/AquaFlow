-- ============================================================
-- V18: 线下支付权限（水站总开关 + 客户×水站授权）
-- ============================================================

-- 1. station 表新增线下支付总开关
ALTER TABLE `station` 
ADD COLUMN `offline_payment_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许线下支付(总开关)' AFTER `status`;

-- 2. 新表：客户×水站权限配置
CREATE TABLE `customer_station_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `customer_id` BIGINT NOT NULL COMMENT '客户ID',
    `station_id` BIGINT NOT NULL COMMENT '水站ID',
    `offline_payment_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '该客户在该站是否允许线下支付',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_customer_station` (`customer_id`, `station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户×水站权限配置';