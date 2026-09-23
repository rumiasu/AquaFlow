-- ============================================================
-- V15: 添加 order_item.water_type_id 列
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE `order_item`
  ADD COLUMN IF NOT EXISTS `water_type_id` bigint DEFAULT NULL COMMENT '水类型ID' AFTER `product_id`;

SET FOREIGN_KEY_CHECKS = 1;