-- ============================================================
-- [AQ-001] 修复 ticket_account 遗留唯一键，使多水票 / 多水站可用
-- ============================================================
-- 问题：
--   ticket_account 上残留一个只按 customer_id 唯一的 uk_customer_water。
--   它导致"同一顾客在同一水站购买第二种水票"直接违反唯一约束，
--   多水票 / 多水站业务在物理上不可用。
--
-- 根因：
--   正确的 uk_customer_product_station (customer_id, product_id, station_id)
--   其实已经存在。旧键之所以没被删掉，是因为
--   migration_p5_6_station_asset_isolation.sql 整段基于已被 product_id 取代的
--   water_type_id 列，该迁移从未执行成功，旧键就一直留在库里。
--
-- 本迁移幂等，可重复执行。
-- ============================================================

-- 1. 删除遗留唯一键 uk_customer_water
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ticket_account'
      AND index_name = 'uk_customer_water'
);
SET @ddl := IF(@idx_exists > 0,
    'ALTER TABLE `ticket_account` DROP INDEX `uk_customer_water`',
    'SELECT ''uk_customer_water not exists, skip''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 第二道防线：station_id 不允许 NULL
--    MySQL 唯一键中 NULL 互不相等，若允许 NULL，则 (customer, product, NULL)
--    可以插入多行，水站隔离会被静默绕过。
--    代码侧 addTicket() 也加了非空校验，此处是数据库级兜底。
SET @nullable := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ticket_account'
      AND column_name = 'station_id'
      AND is_nullable = 'YES'
);
SET @ddl2 := IF(@nullable > 0,
    'ALTER TABLE `ticket_account` MODIFY COLUMN `station_id` bigint NOT NULL COMMENT ''所属水站ID''',
    'SELECT ''station_id already NOT NULL, skip''');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
