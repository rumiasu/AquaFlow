-- =============================================================================
-- V26: 为 deposit_record.related_order_id 补索引
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v26_deposit_record_order_index.sql
--
-- -----------------------------------------------------------------------------
-- 问题
--   deposit_record 原只有 PRIMARY(id) 与 idx_deposit_record_station(station_id) 两个索引，
--   而**按订单查押金流水**这条路径没有索引支撑：
--     DepositRecordMapper.countByOrderAndType(orderId, type)
--       → select count(*) from deposit_record where related_order_id = ? and type = ?
--
--   它被两处热路径调用：
--     1) PaymentServiceImpl.applyDepositOnPaid —— 每次"订单支付成功"都调，用来做入账幂等
--        （线上确认 / 现金收款 / 水票扣减三个入口都会走到它）；
--     2) PaymentServiceImpl.refundOrder —— 取消订单时判断"这笔押金到底入没入过账"。
--
-- 不改会怎样
--   当前 7 行数据毫无影响；但 deposit_record 是**只增不改**的资金流水表，
--   会随订单量线性增长。没有索引时上述两个查询都是**全表扫描**，
--   且发生在支付成功/取消订单这类高频写路径内部（同一事务里），
--   量上来后会同时拖慢支付确认与取消退款。
--
-- 为什么是 (related_order_id, type) 复合索引而不是只建 related_order_id
--   · 唯一实际查询条件是 related_order_id + type，复合索引可直接覆盖，无需回表过滤；
--   · 前缀 related_order_id 仍能服务"只按订单查全部押金流水"的场景
--     （如退款、人工核对），所以不必再单独建一列索引。
--
-- 影响面
--   · 纯新增索引，不改列、不动数据、不改语义；
--   · 表当前 7 行，建索引瞬时完成；
--   · MySQL 8 支持 ONLINE DDL，加二级索引不阻塞读写（本表量级下无感知）。
--
-- 幂等：可重复执行（先查 information_schema.statistics 是否已有同名索引）。
-- 回滚：DROP INDEX idx_deposit_record_order ON deposit_record;
--
-- 相关：本索引同时让下面这条"按订单追踪押金"的排查语句走索引 ——
--   SELECT id, customer_id, station_id, type, amount, related_order_id
--     FROM deposit_record WHERE related_order_id = <订单ID> ORDER BY id;
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record');
SET @s := IF(@has_tbl>0,
  "SELECT '开始为 deposit_record 补 related_order_id 索引（V26）' AS note",
  "SELECT 'ABORT: 当前库没有 deposit_record 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 预检：索引是否已存在（整个脚本的幂等开关）
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record'
                   AND INDEX_NAME='idx_deposit_record_order');

-- 2) 留痕：建索引前打印当前索引清单（重复执行时跳过）
SET @s := IF(@has_tbl>0,
  "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
     FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='deposit_record'
    GROUP BY INDEX_NAME",
  "SELECT 'skip' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 建索引（已存在则跳过）
SET @s := IF(@has_idx=0,
  "ALTER TABLE deposit_record ADD INDEX idx_deposit_record_order (related_order_id, type)",
  "SELECT 'skip: idx_deposit_record_order 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) 校验 A：索引应存在且列顺序为 related_order_id, type
SELECT '校验A：deposit_record 索引清单（应含 idx_deposit_record_order）' AS check_item;
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record'
 GROUP BY INDEX_NAME;

-- 5) 校验 B：确认该索引真的被用于目标查询（type 不应是 ALL 全表扫描）
SELECT '校验B：目标查询执行计划（key 应为 idx_deposit_record_order）' AS check_item;
EXPLAIN SELECT COUNT(*) FROM deposit_record WHERE related_order_id = 1 AND type = 5;

-- 6) 校验 C：行数未受影响
SELECT '校验C：行数（建索引不应改变数据）' AS check_item;
SELECT COUNT(*) AS rows_after_index FROM deposit_record;
