-- =============================================================================
-- V25: 归档旧欠桶台账 customer_owed_barrel（备份 + 退场，不 DROP）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v25_retire_customer_owed_barrel.sql
--
-- ⚠️ 本脚本会把 customer_owed_barrel **改名**（RENAME），不再是原名。
--    执行前请确认已获业务方同意，并已备份整库。
--
-- -----------------------------------------------------------------------------
-- 为什么可以退场（2026-09-12 核实，均为实测而非推断）
--   1) 数据：真实库 customer_owed_barrel 当前 **0 行**（`SELECT COUNT(*)` = 0）。
--   2) 代码：全仓零读写 ——
--      · 实体 CustomerBarrelOwed 与 Mapper CustomerBarrelOwedMapper 已删除；
--      · 4 处 @Autowired 死注入（BarrelController / BarrelServiceImpl /
--        CustomerServiceImpl / OrderServiceImpl）已删除；
--      · 客户画像的欠桶数走 CustomerMapper.getOwedBarrels，其 SQL 读的是
--        **customer_barrel_over**（按商品、可为负），与旧表无关；
--      · CustomerMapper.countAll / OrderMapper.countAll / countToday / countByStatus
--        等全平台口径死方法已一并删除。
--   3) 运行期校验：ReconciliationService 的等式3b/3c 与 V2 的 E4 读的都是
--      customer_barrel_over；本仓库的 daily_reconcile.sql 里唯一还引用旧表的那条
--      （3c 欠桶数量为负）已改为 over 越界判定 —— 那个判定本身也是失效的：
--      旧表"只增不减"，owed_qty 永远不会为负。
--
--   模型替代关系：欠桶 = customer_barrel_over.over_qty > 0（按 customer×station×product），
--   over < 0 表示顾客多还桶/水站暂存，是合法状态。
--
-- -----------------------------------------------------------------------------
-- 幂等：可重复执行。
--   · 备份表用 CREATE TABLE IF NOT EXISTS，不会覆盖已有备份；
--   · RENAME 前用 information_schema 预检原表是否存在，已改名则整体 skip。
-- 回滚：
--   RENAME TABLE bak_v25_customer_owed_barrel TO customer_owed_barrel;
--   （若期间已有新备份，先确认再覆盖）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_orders := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders');
SET @s := IF(@has_orders>0,
  "SELECT '开始归档 customer_owed_barrel（V25）' AS note",
  "SELECT 'ABORT: 当前库没有 orders 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 预检：原表是否还在原名下（整个脚本的幂等开关）
SET @n := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_owed_barrel');

-- 2) 留痕：打印将被归档的数据量（重复执行时跳过，否则表已改名会报错）
SET @s := IF(@n>0,
  "SELECT COUNT(*) AS rows_before_retire FROM customer_owed_barrel",
  "SELECT 'skip: customer_owed_barrel 已不在原名下（脚本重复执行）' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 备份（只建一次，重复执行不覆盖已有备份）
SET @s := IF(@n>0,
  "CREATE TABLE IF NOT EXISTS bak_v25_customer_owed_barrel AS SELECT * FROM customer_owed_barrel",
  "SELECT 'skip: 无需备份（原表已归档）' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@n>0,
  "SELECT CONCAT('备份表 bak_v25_customer_owed_barrel 行数 = ', COUNT(*)) AS backup_rows FROM bak_v25_customer_owed_barrel",
  "SELECT 'skip' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) 改名退场（不 DROP：正式删表需另行拍板；改名后新代码引用同名表会立刻报错，
--    这是刻意保留的一道"误引用哨兵"）
SET @s := IF(@n>0,
  "RENAME TABLE customer_owed_barrel TO bak_v25_customer_owed_barrel_retired",
  "SELECT 'skip: customer_owed_barrel 已归档，无操作' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5) 校验 A：原名下应已无该表（应为空集）
SELECT '校验A：customer_owed_barrel 原名（应为空）' AS check_item;
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_owed_barrel';

-- 6) 校验 B：备份表应存在
SELECT '校验B：归档备份表（应存在一条）' AS check_item;
SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('bak_v25_customer_owed_barrel','bak_v25_customer_owed_barrel_retired');

-- 7) 校验 C：新的欠桶真相源应存在
SELECT '校验C：customer_barrel_over（欠桶新真相源，应存在）' AS check_item;
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_over';
