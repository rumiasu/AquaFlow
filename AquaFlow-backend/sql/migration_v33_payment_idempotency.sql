-- =============================================================================
-- V33: 在线购票幂等键（payment_record.idempotency_key）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v33_payment_idempotency.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17 核实，缺陷已存在、非新引入）
--
--   在线购票 `POST /api/tickets/purchase` 此前**完全没有防重**：
--     · `TicketAccountServiceImpl.purchaseTicket` 不设 orderId（无订单支付），
--       插入 payment_record 前不做任何存在性检查；
--     · `PaymentServiceImpl.createPayment` 的存在性检查整段包在 `if (orderId != null)` 里，
--       无订单支付直接跳过；
--     · 数据库层的 `uk_payment_active_order` 建在生成列 `active_order_id` 上，其定义为
--       `case when status in (1,2) then order_id else NULL end` —— order_id 为 NULL 时
--       生成列也是 NULL，而 **MySQL 唯一键中 NULL 互不冲突**，因此该路径零保护。
--
--   后果：客户连点两次"买 100 张票" → 两条 PENDING 流水；站长在「待确认收款」列表里
--   看到两行几乎一样的记录，很可能两条都确认 → `PaymentServiceImpl.confirmPayment` 的
--   乐观锁（CAS PENDING→PAID）只保证**单条流水**只入账一次，两条流水就会**入账两次**。
--   金额越大越疼，而水票档位套餐（10/20/100 张）正是大额场景。
--
--   同形状的坑本仓已有先例：`uk_ticket_consume(order_id, product_id, source)` 在
--   order_id IS NULL 时同样零保护（见 sql/schema.sql 该表注释）。
--
-- 方案：加客户端幂等键，唯一键建在 (customer_id, idempotency_key) 上
--   · 单列唯一键不行 —— 客户端可编造 token，若按 token 单独查重，A 传了 B 的 token
--     就会拿回 B 的支付记录（跨客户信息泄露）。带上 customer_id 即天然隔离。
--   · idempotency_key 为 NULL 时整行不参与唯一性判定（索引中存在 NULL 列即不视为重复），
--     所以**全部存量行与所有订单支付（order_id 非空）完全不受影响**。
--   · 与 orders.idempotency_key（uk idx_orders_idempotency_key）是同一套做法。
--
-- 影响面
--   · 纯新增 1 列 + 1 个唯一键，**不改任何既有列的类型与含义、不动任何存量数据**；
--   · 存量行 idempotency_key 全为 NULL → 新唯一键对它们零约束；
--   · 调用方：`POST /api/tickets/purchase` 变为**必传** idempotencyKey（缺失返回 code=1）。
--     该端点唯一调用方是 miniapp-user（api/ticket.js → pages/ticket/index.js），已同批更新。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS，索引查 information_schema.STATISTICS）。
-- 回滚：ALTER TABLE payment_record DROP INDEX uk_payment_idempotency, DROP COLUMN idempotency_key;
--       （回滚只失去防重能力，不影响任何金额与余额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record');
SET @s := IF(@has_tbl>0,
  "SELECT '开始执行 V33（在线购票幂等键）' AS note",
  "SELECT 'ABORT: 当前库没有 payment_record 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) idempotency_key 列
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record'
                AND COLUMN_NAME='idempotency_key')=0,
  "ALTER TABLE payment_record ADD COLUMN idempotency_key varchar(64) NULL DEFAULT NULL COMMENT '客户端幂等键（在线购票等无订单支付用）；NULL=不参与防重' AFTER order_id",
  "SELECT 'skip: payment_record.idempotency_key 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 唯一键 (customer_id, idempotency_key)
--    加之前先确认存量数据不会撞键：idempotency_key 刚加时全为 NULL，理论上必不冲突，
--    但仍做一次显式预检，避免在"已部分执行过、且已有非 NULL 数据"的库上失败。
SET @dup := (SELECT COUNT(*) FROM (
               SELECT customer_id, idempotency_key
                 FROM payment_record
                WHERE idempotency_key IS NOT NULL
                GROUP BY customer_id, idempotency_key
               HAVING COUNT(*) > 1) t);
SET @s := IF(@dup > 0,
  "SELECT 'ABORT: 已存在重复的 (customer_id, idempotency_key)，请先人工清理后再加唯一键' AS note",
  IF((SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record'
        AND INDEX_NAME='uk_payment_idempotency')=0,
     "ALTER TABLE payment_record ADD UNIQUE KEY uk_payment_idempotency (customer_id, idempotency_key)",
     "SELECT 'skip: uk_payment_idempotency 已存在' AS r"));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 校验
SELECT 'V33 完成：在线购票幂等键已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record'
   AND COLUMN_NAME='idempotency_key';

SELECT '校验B：唯一键已建立' AS check_item;
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record'
   AND INDEX_NAME='uk_payment_idempotency'
 ORDER BY SEQ_IN_INDEX;

SELECT '校验C：存量数据未受影响（应为 0）' AS check_item;
SELECT COUNT(*) AS non_null_idempotency_rows FROM payment_record WHERE idempotency_key IS NOT NULL;
SELECT (SELECT COUNT(*) FROM payment_record) AS payment_rows,
       (SELECT COUNT(*) FROM orders) AS orders_rows;
