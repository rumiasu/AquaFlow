-- ============================================================
-- V14: 添加订单金额字段，使 orders 表与 MyBatis Mapper 对齐
-- 基于 schema_v1_final.sql 的 orders 表结构
-- 执行前请备份数据库
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 添加缺失的金额字段
ALTER TABLE `orders`
  ADD COLUMN IF NOT EXISTS `total_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额' AFTER `payment_status`,
  ADD COLUMN IF NOT EXISTS `water_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '水费金额' AFTER `total_amount`,
  ADD COLUMN IF NOT EXISTS `deposit_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '押金金额' AFTER `water_amount`;

-- 2. 添加缺失的站点字段
ALTER TABLE `orders`
  ADD COLUMN IF NOT EXISTS `owner_station_id` bigint DEFAULT NULL COMMENT '归属站(客户归属站)' AFTER `address_id`,
  ADD COLUMN IF NOT EXISTS `delivery_station_id` bigint DEFAULT NULL COMMENT '实际履约配送水站ID' AFTER `owner_station_id`;

-- 3. 更新现有数据：从 station_id 迁移到 owner_station_id/delivery_station_id
UPDATE `orders` 
SET `owner_station_id` = COALESCE(`owner_station_id`, `station_id`),
    `delivery_station_id` = COALESCE(`delivery_station_id`, `station_id`)
WHERE `owner_station_id` IS NULL OR `delivery_station_id` IS NULL;

-- 4. 为 owner_station_id 和 delivery_station_id 添加索引
SET @has_idx_owner = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_owner_station');

SET @sql1 = IF(@has_idx_owner = 0,
  'ALTER TABLE `orders` ADD INDEX `idx_orders_owner_station` (`owner_station_id`)',
  'SELECT ''idx_orders_owner_station already exists''');
PREPARE stmt1 FROM @sql1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @has_idx_delivery = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_delivery_station');

SET @sql2 = IF(@has_idx_delivery = 0,
  'ALTER TABLE `orders` ADD INDEX `idx_orders_delivery_station` (`delivery_station_id`)',
  'SELECT ''idx_orders_delivery_station already exists''');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 5. 验证迁移结果
-- SELECT COUNT(*) FROM `orders` WHERE `total_amount` = 0 AND `water_amount` = 0 AND `deposit_amount` = 0;
-- DESCRIBE `orders`;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 回滚脚本
-- ============================================================
/*
SET FOREIGN_KEY_CHECKS = 0;
ALTER TABLE `orders` DROP COLUMN IF EXISTS `total_amount`;
ALTER TABLE `orders` DROP COLUMN IF EXISTS `water_amount`;
ALTER TABLE `orders` DROP COLUMN IF EXISTS `deposit_amount`;
ALTER TABLE `orders` DROP COLUMN IF EXISTS `owner_station_id`;
ALTER TABLE `orders` DROP COLUMN IF EXISTS `delivery_station_id`;
DROP INDEX IF EXISTS `idx_orders_owner_station` ON `orders`;
DROP INDEX IF EXISTS `idx_orders_delivery_station` ON `orders`;
SET FOREIGN_KEY_CHECKS = 1;
*/