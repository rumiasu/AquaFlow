-- 清理已确认废弃的库表/字段（2026-09-11）
-- 1) water_type 表：与 product 表功能重叠，已被 orders.product_id 取代；0 行数据、0 代码引用。
--    指向它的孤儿外键 fk_orders_water_type 已随 orders.water_type_id 列删除而成为失效元数据
--    （DROP FOREIGN KEY 报 1091 列/键不存在），不再约束本表删除，故直接 DROP。
-- 2) customer.deposit_balance：AQ-054 确认恒 0 且展示改用 customer_deposit_account，
--    当前源码对该列零读零写（CustomerStationVO 改读 deposit_account，CustomerMapper 更新语句不碰它），
--    属纯死列。
-- 本脚本幂等：DROP TABLE IF EXISTS 原生支持；列删除先经 information_schema 判定再执行，
-- 重复运行（含 Flyway 下次启动重放）不会报错。注意本环境 MySQL 8.4 不支持
-- DROP COLUMN IF EXISTS / DROP FOREIGN KEY IF EXISTS 语法，故用判定式写法。

DROP TABLE IF EXISTS water_type;

-- 仅当 deposit_balance 列仍存在时才删除，避免 Flyway 重放时报 1091
SET @dep_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'aquaflow' AND TABLE_NAME = 'customer' AND COLUMN_NAME = 'deposit_balance');
SET @dep_sql = IF(@dep_exists > 0, 'ALTER TABLE customer DROP COLUMN deposit_balance', 'SELECT 1');
PREPARE dep_stmt FROM @dep_sql;
EXECUTE dep_stmt;
DEALLOCATE PREPARE dep_stmt;
