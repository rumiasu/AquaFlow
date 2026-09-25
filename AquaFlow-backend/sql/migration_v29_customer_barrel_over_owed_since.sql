-- =============================================================================
-- V29: customer_barrel_over 增加 owed_since（欠桶起始时间）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v29_customer_barrel_over_owed_since.sql
--
-- -----------------------------------------------------------------------------
-- 为什么需要这一列
--   站长要能回答「这个客户欠桶欠了多少天」。`customer_barrel_over` 只有
--   over_qty（净额）与 create_time / update_time（**行**的生命周期），
--   答不了这个问题：
--     · over 归零后再欠，create_time 不会重置；
--     · update_time 会被任何一次 over 变更刷新（含"还了一部分"），
--       用它当起始时间会让天数永远显示为"今天"。
--   所以必须单独记一个"本次欠桶从哪天开始"。
--
-- 语义（应用侧唯一维护点：BarrelLedgerService 调 mapper.syncOwedSince）
--   · over_qty 从 <= 0 变为 > 0  → 写入当前时间（本次欠桶开始）
--   · over_qty 回到 <= 0         → 置 NULL（不再欠桶）
--   · over_qty 已是正数且再增加  → **保持原值**（同一笔欠桶的延续，天数不清零）
--   · over_qty < 0（水站暂存）   → NULL，不算欠桶
--
-- ⚠️ 这一列**只用于展示与运营提醒**（站长端欠桶台账 / 下单标红）。
--    它不是任何校验的依据：欠桶的物理约束仍是「占用 = 权益 + over ≥ 0」，
--    由 customer_barrel_over.over_qty 本身体现（见 CustomerBarrelOver 类注释）。
--
-- 影响面
--   · 纯新增 1 个可空列，不改既有列、不改任何数据、不改查询语义；
--   · 表量级小（每 (客户,水站,桶品) 一行），ALTER 走 ONLINE DDL，不阻塞读写。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS）。
-- 回滚：ALTER TABLE customer_barrel_over DROP COLUMN owed_since;
--       （回滚只丢"欠了几天"的展示信息，不影响桶账恒等式与任何金额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_over');
SET @s := IF(@has_tbl>0,
  "SELECT '开始执行 V29（customer_barrel_over 增加 owed_since）' AS note",
  "SELECT 'ABORT: 当前库没有 customer_barrel_over 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 加列（已存在则跳过）
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_over'
                AND COLUMN_NAME='owed_since')=0,
  "ALTER TABLE customer_barrel_over ADD COLUMN owed_since datetime NULL DEFAULT NULL COMMENT '本次欠桶起始时间: over 由<=0变为>0时写入, 回到<=0时清空, 已是正数再增加不重置; NULL=当前不欠桶(含 over<0 的水站暂存)。仅供站长端欠桶台账/下单提醒展示, 不参与任何校验' AFTER update_time",
  "SELECT 'skip: customer_barrel_over.owed_since 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 存量回填（近似值）
--    历史行没有记录起始时间，只能用 update_time 作为**近似**起点。
--    只回填当前确实欠桶的行（over_qty > 0）；其余保持 NULL。
--    幂等：条件里带 owed_since IS NULL，重复执行不会覆盖应用侧写入的真实值。
UPDATE customer_barrel_over
   SET owed_since = update_time
 WHERE over_qty > 0 AND owed_since IS NULL;

-- 3) 校验
SELECT '校验A：列应存在且可空' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_over' AND COLUMN_NAME='owed_since';

SELECT '校验B：取值分布（over>0 的行应有 owed_since；over<=0 的行应为 NULL）' AS check_item;
SELECT CASE WHEN over_qty > 0 THEN '欠桶(over>0)' WHEN over_qty = 0 THEN '正常(=0)' ELSE '暂存(<0)' END AS kind,
       CASE WHEN owed_since IS NULL THEN 'owed_since=NULL' ELSE 'owed_since=有值' END AS owed_since_state,
       COUNT(*) AS cnt
  FROM customer_barrel_over
 GROUP BY kind, owed_since_state
 ORDER BY kind, owed_since_state;

SELECT '校验C：行数未受影响' AS check_item;
SELECT COUNT(*) AS customer_barrel_over_rows FROM customer_barrel_over;
