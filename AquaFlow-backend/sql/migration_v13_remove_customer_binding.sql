-- ============================================================
-- V13: 移除客户永久绑定水站逻辑
-- 核心变更：客户不再有永久归属水站，资产按 (customer_id, station_id) 隔离
-- 执行前提：备份数据库
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. 迁移现有数据：将 station_id/owner_station_id 同步到 current_station_id
-- ============================================================
-- 优先级：current_station_id > owner_station_id > station_id
UPDATE `customer` 
SET `current_station_id` = COALESCE(`current_station_id`, `owner_station_id`, `station_id`)
WHERE `current_station_id` IS NULL;

-- ============================================================
-- 2. 删除客户表不再需要的绑定字段
-- ============================================================
ALTER TABLE `customer` DROP COLUMN `station_id`;
ALTER TABLE `customer` DROP COLUMN `owner_station_id`;
ALTER TABLE `customer` DROP COLUMN `owner_staff_id`;
ALTER TABLE `customer` DROP COLUMN `bind_time`;
ALTER TABLE `customer` DROP COLUMN `bind_reason`;

-- ============================================================
-- 3. 删除客户换站记录表 (不再需要，资产天然隔离)
-- ============================================================
DROP TABLE IF EXISTS `customer_station_record`;

-- ============================================================
-- 4. 订单表：owner_station_id 保留 (表示下单时的服务水站)
--    delivery_station_id 保留 (表示实际履约水站)
--    无需变更，现有设计已支持
-- ============================================================

-- ============================================================
-- 5. 验证迁移结果
-- ============================================================
-- 验证客户表字段
-- DESCRIBE `customer`;

-- 验证无 NULL current_station_id (除非从未选站)
-- SELECT COUNT(*) FROM `customer` WHERE `current_station_id` IS NULL;

-- 验证资产表已有 station_id (P5.6 已迁移)
-- SELECT COUNT(*) FROM `customer_barrel_asset` WHERE `station_id` IS NULL;
-- SELECT COUNT(*) FROM `ticket_account` WHERE `station_id` IS NULL;
-- SELECT COUNT(*) FROM `customer_deposit_account` WHERE `station_id` IS NULL;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 回滚脚本 (如需回滚)
-- ============================================================
/*
SET FOREIGN_KEY_CHECKS = 0;
ALTER TABLE `customer`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '当前水站ID' AFTER `openid`,
  ADD COLUMN `owner_station_id` bigint DEFAULT NULL COMMENT '归属水站ID' AFTER `station_id`,
  ADD COLUMN `owner_staff_id` bigint DEFAULT NULL COMMENT '绑定/负责业务员' AFTER `owner_station_id`,
  ADD COLUMN `bind_time` datetime DEFAULT NULL COMMENT '绑站时间' AFTER `owner_staff_id`,
  ADD COLUMN `bind_reason` varchar(100) DEFAULT NULL COMMENT '绑站原因' AFTER `bind_time`;

UPDATE `customer` SET `station_id` = `current_station_id`, `owner_station_id` = `current_station_id` WHERE `current_station_id` IS NOT NULL;

CREATE TABLE `customer_station_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `from_station_id` bigint DEFAULT NULL COMMENT '原水站',
  `to_station_id` bigint DEFAULT NULL COMMENT '新水站',
  `reason` varchar(200) DEFAULT NULL COMMENT '换站原因',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
*/