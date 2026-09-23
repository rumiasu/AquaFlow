-- ============================================================
-- P5.6 Station Asset Isolation Migration
-- 客户资产按水站隔离
-- 
-- 涉及表：
--   1. ticket_account          (B01)
--   2. customer_barrel_asset   (B02)
--   3. customer_deposit_account (B03 - 新表)
--   4. deposit_record          (B03)
--   5. customer_owed_barrel    (H06)
--   6. order_template          (H02)
--   7. payment_record          (H07)
--   8. barrel_record           (退桶记录 - 不需要station_id, 通过customer归属)
--
-- 执行前提：
--   - 备份数据库
--   - 确认 customer.owner_station_id 数据完整
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. ticket_account (B01): 增加 station_id
-- ============================================================
-- 1.1 增加列
ALTER TABLE `ticket_account`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `water_type_id`;

-- 1.2 迁移历史数据：按客户归属站分配
-- 规则：优先 owner_station_id，其次 current_station_id，最后 station_id
UPDATE `ticket_account` ta
  INNER JOIN `customer` c ON ta.customer_id = c.id
  SET ta.station_id = COALESCE(c.owner_station_id, c.current_station_id, c.station_id)
  WHERE ta.station_id IS NULL;

-- 1.3 删除旧唯一索引，创建新唯一索引
ALTER TABLE `ticket_account`
  DROP INDEX IF EXISTS `uk_customer_water`,
  ADD UNIQUE KEY `uk_customer_water_station` (`customer_id`, `water_type_id`, `station_id`),
  ADD KEY `idx_ticket_station` (`station_id`);

-- ============================================================
-- 2. customer_barrel_asset (B02): 增加 station_id
-- ============================================================
-- 2.1 增加列
ALTER TABLE `customer_barrel_asset`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `water_type_id`;

-- 2.2 迁移历史数据
UPDATE `customer_barrel_asset` cba
  INNER JOIN `customer` c ON cba.customer_id = c.id
  SET cba.station_id = COALESCE(c.owner_station_id, c.current_station_id, c.station_id)
  WHERE cba.station_id IS NULL;

-- 2.3 删除旧唯一索引，创建新唯一索引
ALTER TABLE `customer_barrel_asset`
  DROP INDEX IF EXISTS `uk_asset`,
  ADD UNIQUE KEY `uk_asset_station` (`customer_id`, `water_type_id`, `station_id`),
  ADD KEY `idx_barrel_asset_station` (`station_id`);

-- ============================================================
-- 3. customer_deposit_account (B03 - 新表): 按水站隔离的押金余额
-- ============================================================
CREATE TABLE IF NOT EXISTS `customer_deposit_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '押金余额',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_station` (`customer_id`, `station_id`),
  KEY `idx_deposit_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户按水站隔离的押金余额';

-- 3.1 迁移历史数据：从 customer.deposit_balance 按归属站拆分
-- 规则：客户全局押金余额归入其归属站
INSERT INTO `customer_deposit_account` (`customer_id`, `station_id`, `balance`, `update_time`)
SELECT
  c.id AS customer_id,
  COALESCE(c.owner_station_id, c.current_station_id, c.station_id) AS station_id,
  COALESCE(c.deposit_balance, 0) AS balance,
  NOW() AS update_time
FROM `customer` c
WHERE COALESCE(c.deposit_balance, 0) > 0
  AND COALESCE(c.owner_station_id, c.current_station_id, c.station_id) IS NOT NULL
ON DUPLICATE KEY UPDATE
  `balance` = VALUES(`balance`),
  `update_time` = NOW();

-- ============================================================
-- 4. deposit_record (B03): 增加 station_id
-- ============================================================
-- 4.1 增加列
ALTER TABLE `deposit_record`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `customer_id`;

-- 4.2 迁移历史数据
UPDATE `deposit_record` dr
  INNER JOIN `customer` c ON dr.customer_id = c.id
  SET dr.station_id = COALESCE(c.owner_station_id, c.current_station_id, c.station_id)
  WHERE dr.station_id IS NULL;

-- 4.3 添加索引
ALTER TABLE `deposit_record`
  ADD KEY `idx_deposit_record_station` (`station_id`);

-- ============================================================
-- 5. customer_owed_barrel (H06): 增加 station_id
-- ============================================================
-- 5.1 增加列
ALTER TABLE `customer_owed_barrel`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `water_type_id`;

-- 5.2 迁移历史数据：欠桶归属于客户归属站
UPDATE `customer_owed_barrel` cob
  INNER JOIN `customer` c ON cob.customer_id = c.id
  SET cob.station_id = COALESCE(c.owner_station_id, c.current_station_id, c.station_id)
  WHERE cob.station_id IS NULL;

-- 5.3 删除旧唯一索引，创建新唯一索引
ALTER TABLE `customer_owed_barrel`
  DROP INDEX IF EXISTS `uk_owed`,
  ADD UNIQUE KEY `uk_owed_station` (`customer_id`, `water_type_id`, `station_id`),
  ADD KEY `idx_owed_station` (`station_id`);

-- ============================================================
-- 6. order_template (H02): 增加 station_id
-- ============================================================
-- 6.1 增加列
ALTER TABLE `order_template`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `customer_id`;

-- 6.2 迁移历史数据：模板归属站
UPDATE `order_template` ot
  INNER JOIN `customer` c ON ot.customer_id = c.id
  SET ot.station_id = COALESCE(c.current_station_id, c.owner_station_id, c.station_id)
  WHERE ot.station_id IS NULL;

-- 6.3 添加索引
ALTER TABLE `order_template`
  ADD KEY `idx_template_station` (`station_id`);

-- ============================================================
-- 7. payment_record (H07): 增加 station_id
-- ============================================================
-- 7.1 增加列
ALTER TABLE `payment_record`
  ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT '所属水站ID(来自订单)' AFTER `customer_id`;

-- 7.2 迁移历史数据：从关联订单获取 station_id
UPDATE `payment_record` pr
  INNER JOIN `orders` o ON pr.order_id = o.id
  SET pr.station_id = COALESCE(o.delivery_station_id, o.station_id)
  WHERE pr.order_id IS NOT NULL AND pr.station_id IS NULL;

-- 7.3 无关联订单的支付记录（如水票购买），按客户归属站
UPDATE `payment_record` pr
  INNER JOIN `customer` c ON pr.customer_id = c.id
  SET pr.station_id = COALESCE(c.owner_station_id, c.current_station_id, c.station_id)
  WHERE pr.order_id IS NULL AND pr.station_id IS NULL;

-- 7.4 添加索引
ALTER TABLE `payment_record`
  ADD KEY `idx_payment_station` (`station_id`);

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 迁移验证
-- ============================================================
-- 验证 ticket_account 无 NULL station_id
-- SELECT COUNT(*) AS null_ticket_station FROM ticket_account WHERE station_id IS NULL;
-- 验证 customer_barrel_asset 无 NULL station_id
-- SELECT COUNT(*) AS null_barrel_asset_station FROM customer_barrel_asset WHERE station_id IS NULL;
-- 验证 customer_deposit_account 数据完整
-- SELECT COUNT(*) AS deposit_account_count FROM customer_deposit_account WHERE balance > 0;
-- 验证 deposit_record 无 NULL station_id
-- SELECT COUNT(*) AS null_deposit_record_station FROM deposit_record WHERE station_id IS NULL;
-- 验证 customer_owed_barrel 无 NULL station_id
-- SELECT COUNT(*) AS null_owed_station FROM customer_owed_barrel WHERE station_id IS NULL;
-- 验证 order_template 无 NULL station_id
-- SELECT COUNT(*) AS null_template_station FROM order_template WHERE station_id IS NULL;
-- 验证 payment_record 无 NULL station_id
-- SELECT COUNT(*) AS null_payment_station FROM payment_record WHERE station_id IS NULL;
