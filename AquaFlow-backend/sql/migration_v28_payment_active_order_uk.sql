-- =============================================================================
-- V28: 为 payment_record 建立「一单一条活跃流水」的数据库级防重
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v28_payment_active_order_uk.sql
--
-- -----------------------------------------------------------------------------
-- 问题（审计 P0-5）
--   `PaymentServiceImpl.createPayment` 的防重是 **check-then-act**：
--     ① 先 `getByOrderId(orderId)`，若状态为 PAID/PENDING 则直接返回原记录；
--     ② 否则继续执行：水票单还会先 `deductTickets(orderId)` 扣票，再 `insert` 支付流水。
--   两个并发请求可以同时通过 ①，于是**都**走到 ②：
--     · 水票被扣两次（第二次撞 `uk_ticket_consume` 才被吞掉，扣减本身不回滚）；
--     · 两条 PENDING/PAID 流水各自触发 `applyDepositOnPaid` → 押金账户被入账两次；
--     · 两条 deposit_record 让「押金余额 == 流水合计」的对账等式仍然成立，
--       即**账面查不出**，直到客户取消订单时按两条 PAID 流水**各退一次**。
--
--   原来的数据库兜底是 `uk_payment_order_status(order_id,status)`，但它与
--   「退款另立负金额冲正流水」的设计冲突（退款后原记录 = REFUNDED、冲正流水也 = REFUNDED，
--   (order_id, 已退款) 必然重复），已被降级为普通索引（见 schema.sql 的 [DEF-3] 注释），
--   于是并发防重的责任全部落在应用层的 ①，而 ① 挡不住并发。
--
-- 本迁移的做法（MySQL 无部分索引，用 STORED 生成列模拟）
--   `active_order_id` = 仅当 `status in (1,2)`（活跃态：待收款/已付）时取 `order_id`，否则 NULL。
--   对 `active_order_id` 建唯一键 ⇒ 同一订单最多一条活跃流水；退款后原记录与冲正流水
--   都不是活跃态，生成列为 NULL，互不冲突（NULL 在唯一键中不参与比较）。
--   无订单支付（水票直购）order_id 为 NULL，生成列亦为 NULL，同样不受约束。
--
-- 不改会怎样
--   并发重复提交（网络重试、用户连点、小程序重复请求）即可造成重复扣票与重复入账押金，
--   且对账查不出，属资金正确性缺陷。
--
-- 影响面
--   · 新增 1 个 STORED 生成列 + 1 个唯一键，不改既有列、不改数据、不改查询语义；
--   · 生成列由 MySQL 自动维护（INSERT/UPDATE 时重算），应用侧无需写入该列；
--   · 加唯一键会重建表（TABLE 级别 DDL），表量级小，MySQL 8 的 ONLINE DDL 下不阻塞读写。
--
-- ⚠️ 执行前必须先跑「第 1 步：预检」：若历史数据里已存在同一订单的多条活跃流水，
--    加唯一键会失败（Duplicate entry）。此时**不要**让脚本自动删数据——那属于资金数据，
--    必须人工核对后处理（保留哪一条、其余如何冲正），处理完再重跑本脚本。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS，唯一键查 information_schema.STATISTICS）。
-- 回滚：
--   ALTER TABLE payment_record DROP INDEX uk_payment_active_order, DROP COLUMN active_order_id;
--   （回滚只撤销约束，不改变任何资金数据；回滚后并发防重退回纯应用层。）
--
-- 相关：审计报告 AQ-EVL-20260913 的 P0-5；设计书 AQ-DDD-003（排他制御と整合性）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record');
SET @s := IF(@has_tbl>0,
  "SELECT '开始执行 V28（payment_record 活跃流水唯一键）' AS note",
  "SELECT 'ABORT: 当前库没有 payment_record 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 1) 预检（只读）：列出已存在的「同一订单多条活跃流水」
--    结果为空 = 可以安全加唯一键；非空 = 必须先人工处理，不要继续执行第 3 步
-- -----------------------------------------------------------------------------
SELECT '预检：同一订单存在多条活跃流水（应为空；非空则须人工处理后重跑）' AS check_item;
SELECT order_id, status, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY id) AS record_ids
  FROM payment_record
 WHERE status IN (1,2) AND order_id IS NOT NULL
 GROUP BY order_id, status
HAVING cnt > 1;

-- -----------------------------------------------------------------------------
-- 2) 加 STORED 生成列（已存在则跳过）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND COLUMN_NAME='active_order_id')=0,
  "ALTER TABLE payment_record ADD COLUMN active_order_id bigint GENERATED ALWAYS AS ((case when (`status` in (1,2)) then `order_id` else NULL end)) STORED COMMENT '仅当流水为活跃态(1待收款/2已付)时等于 order_id，否则 NULL；与 uk_payment_active_order 配合保证一单一条活跃流水'",
  "SELECT 'skip: payment_record.active_order_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 3) 加唯一键（已存在则跳过）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND INDEX_NAME='uk_payment_active_order')=0,
  "ALTER TABLE payment_record ADD UNIQUE KEY uk_payment_active_order (active_order_id)",
  "SELECT 'skip: uk_payment_active_order 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 4) 校验
-- -----------------------------------------------------------------------------
SELECT '校验A：生成列与唯一键应存在' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, EXTRA, GENERATION_EXPRESSION
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND COLUMN_NAME='active_order_id';
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record'
   AND INDEX_NAME IN ('uk_payment_active_order','idx_payment_order_status','idx_order_id')
 GROUP BY INDEX_NAME, NON_UNIQUE;

SELECT '校验B：生成列取值分布（活跃态应等于 order_id，其余为 NULL）' AS check_item;
SELECT CASE WHEN active_order_id IS NULL THEN 'NULL(非活跃/无订单)' ELSE '活跃' END AS kind,
       COUNT(*) AS cnt
  FROM payment_record GROUP BY kind;

SELECT '校验C：行数未受影响' AS check_item;
SELECT COUNT(*) AS payment_record_rows FROM payment_record;
