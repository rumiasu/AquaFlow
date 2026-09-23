-- ============================================================
-- [隔离收尾] 清理 ticket_account 孤儿唯一键 uk_customer_water
-- ============================================================
-- 背景：
--   ticket_account 的隔离由 uk_customer_product_station
--   (customer_id, product_id, station_id) 真实保证（含 station_id）。
--   但运行库曾残留一个 uk_customer_water(customer_id, water_type_id) 孤儿索引元数据，
--   它引用了 ticket_account 表根本不存在的 water_type_id 列（MySQL 8.4 字典不一致），
--   不会约束任何插入，但会让 information_schema.STATISTICS 显示脏数据、部分工具/导出抽风。
--   已于 2026-09-11 用 ALTER TABLE ticket_account FORCE 从 InnoDB 层清除（SHOW INDEX 已验证仅剩正确键）。
--
-- 幂等：仅当孤儿键仍存在时重建表清理；无则跳过。
-- 注意：FORCE 会重建整表（当前仅 4 行，秒级安全）；大表请在低峰期执行一次即可。
-- 验证：SHOW INDEX FROM ticket_account; 应只剩 PRIMARY + uk_customer_product_station
-- ============================================================

SET @orphan := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ticket_account'
      AND index_name = 'uk_customer_water'
);
SET @ddl := IF(@orphan > 0,
    'ALTER TABLE ticket_account FORCE',
    'SELECT 1 AS skip_orphan_cleanup');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
