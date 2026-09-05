-- ============================================================
-- V16: 清理 orders 表站点字段，确立订单归属站 + 实际履约站模型
-- 执行前请备份数据库
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 删除旧的 station_id 字段（全为 NULL，无业务数据）
ALTER TABLE `orders` DROP COLUMN IF EXISTS `station_id`;

-- 2. 将 owner_station_id 重命名为 station_id，语义：订单归属水站
ALTER TABLE `orders` 
CHANGE COLUMN `owner_station_id` `station_id` BIGINT DEFAULT NULL COMMENT '订单归属水站';

-- 3. 调整索引
-- 删除旧索引
DROP INDEX IF EXISTS `idx_orders_owner_station` ON `orders`;
DROP INDEX IF EXISTS `idx_orders_delivery_station` ON `orders`;

-- 新建索引
CREATE INDEX IF NOT EXISTS `idx_orders_station` ON `orders` (`station_id`);
CREATE INDEX IF NOT EXISTS `idx_orders_delivery_station` ON `orders` (`delivery_station_id`);

-- 4. 验证迁移结果
-- SELECT id, station_id, delivery_station_id FROM orders;
-- SHOW INDEX FROM orders;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 回滚脚本
-- ============================================================
/*
SET FOREIGN_KEY_CHECKS = 0;
ALTER TABLE `orders` ADD COLUMN `station_id` BIGINT DEFAULT NULL COMMENT '旧字段' AFTER `factory_id`;
ALTER TABLE `orders` CHANGE COLUMN `station_id` `owner_station_id` BIGINT DEFAULT NULL COMMENT '归属站(客户归属站，配送/领取时保持原站)';
DROP INDEX IF EXISTS `idx_orders_station` ON `orders`;
CREATE INDEX IF NOT EXISTS `idx_orders_owner_station` ON `orders` (`owner_station_id`);
CREATE INDEX IF NOT EXISTS `idx_orders_delivery_station` ON `orders` (`delivery_station_id`);
SET FOREIGN_KEY_CHECKS = 1;
*/