-- =============================================================================
-- v70 · 站长「手工扣票」的客户端幂等键（ticket_record.idempotency_key）
--
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v70_ticket_consume_idempotency.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（台账 F-24；形状与本仓 v33 在线购票**完全同形**）
--
--   `POST /api/tickets/consume`（站长手工扣票）走的是
--   `TicketAccountServiceImpl.consumeTicket`，它的 `orderId` **可选**：
--   站长手工扣票时 orderId 为 NULL，于是数据库唯一键
--   `uk_ticket_consume(order_id, product_id, source)` **零保护** ——
--   MySQL 唯一键中 NULL 互不冲突，`(NULL, p, '消费')` 可以被插无限多条。
--
--   后果：站长连点两次「扣票 10 张」（或网络重试）→ 账户被扣 20 张、
--   批次账被 FIFO 消耗两次，而流水看起来只是两条一模一样的记录。
--   代码里那句 `catch (DuplicateKeyException)` 也兜不住：它只在
--   orderId 非空时才可能被触发。
--
--   同形的先例：v33（在线购票，`payment_record.order_id IS NULL`）与
--   v62（下单幂等作用域）。两次的结论都是同一条 ——
--   **无订单的写路径没有数据库级兜底，只能靠「客户端幂等键 + 带 customer_id 的唯一键」补。**
--
-- 方案（照抄 v33 的形态：可空列 + (customer_id, idempotency_key) 唯一键）
--   · 单列唯一键不行 —— 客户端可编造 key，若只按 key 查重，A 传了 B 的 key
--     就会拿回 B 的流水（跨客户信息泄露）。带上 customer_id 即天然隔离。
--   · `idempotency_key` 为 NULL 时整行不参与唯一性判定（索引中存在 NULL 列即不视为重复），
--     所以**存量行与所有订单内扣票（orderId 非空）完全不受影响** ——
--     订单内扣票的幂等仍由 `uk_ticket_consume(order_id, product_id, source)` 承担。
--
-- 影响面
--   · 纯新增 1 列 + 1 个唯一键，**不改任何既有列的类型与含义、不动任何存量数据**；
--   · 存量行 idempotency_key 全为 NULL → 新唯一键对它们零约束；
--   · 调用方：`POST /api/tickets/consume` 变为**必传** idempotencyKey（缺失 code=1）。
--     该端点**当前没有任何小程序调用方**（`miniapp-user` / `miniapp-delivery` 均未调用），
--     故本次无前端改动；`archive/legacy-web-frontend` 里的常量属留档、不维护。
--   · 与**调整单**那条无订单扣票路径无关：它走 `adjustTicket`，source='人工调整扣减'，
--     幂等由 `uk_ticket_adjustment(adjustment_id, product_id, source)` 承担，本次不碰。
--
-- ⚠️ 不加外键
--   `ticket_record` 本就没有外键（与 `ticket_account` / `ticket_lot` 一致），
--   加了反而让水站无法清理历史站。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS，索引查 information_schema.STATISTICS；
--       每一步都是 `IF(预检, DDL, 'skip: …')` + PREPARE，二次执行全 skip 且退出码 0）。
-- 回滚：ALTER TABLE ticket_record DROP INDEX uk_ticket_consume_idem, DROP COLUMN idempotency_key;
--       （回滚只失去防重能力，不影响任何余额与批次账 —— 但**必须先回代码**，
--        否则新代码写 idempotency_key 会撞 1054。）
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检（依赖表必须在）
-- -----------------------------------------------------------------------------
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record');
SELECT IF(@has_tbl > 0, 'OK: ticket_record 在',
          CONCAT('ABORT: 当前库没有 ticket_record 表（命中 ', @has_tbl, '/1）—— 请确认连的是 aquaflow 库')) AS precheck;

SET @abort := IF(@has_tbl = 0, 'SELECT * FROM __ABORT_V70_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：idempotency_key 列（可空；NULL = 不参与防重）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
                AND COLUMN_NAME='idempotency_key')=0,
  "ALTER TABLE ticket_record ADD COLUMN idempotency_key varchar(64) NULL DEFAULT NULL COMMENT '客户端幂等键（站长手工扣票等无订单扣票用）；NULL=不参与防重。见 migration_v70' AFTER order_id",
  "SELECT 'skip: ticket_record.idempotency_key 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 2 步：唯一键 (customer_id, idempotency_key)
--   加之前先做一次显式撞键预检：列刚加时全为 NULL、理论必不冲突，
--   但在"已部分执行过、且已有非 NULL 数据"的库上重跑必须能看清原因而不是报 1062。
-- -----------------------------------------------------------------------------
SET @dup := (SELECT COUNT(*) FROM (
               SELECT customer_id, idempotency_key
                 FROM ticket_record
                WHERE idempotency_key IS NOT NULL
                GROUP BY customer_id, idempotency_key
               HAVING COUNT(*) > 1) t);
SET @s := IF(@dup > 0,
  "SELECT 'ABORT: 已存在重复的 (customer_id, idempotency_key)，请先人工核对后再加唯一键' AS note",
  IF((SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
        AND INDEX_NAME='uk_ticket_consume_idem')=0,
     "ALTER TABLE ticket_record ADD UNIQUE KEY uk_ticket_consume_idem (customer_id, idempotency_key)",
     "SELECT 'skip: uk_ticket_consume_idem 已存在' AS r"));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 3 步：自查（期望 column_ready=1 / uk_cols=2 / non_null_rows=0）
-- -----------------------------------------------------------------------------
SELECT 'v70 完成：站长手工扣票幂等键已就绪' AS note;

SELECT '校验A：列已建立' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
   AND COLUMN_NAME='idempotency_key';

SELECT '校验B：唯一键已建立（期望 2 行：customer_id → idempotency_key）' AS check_item;
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
   AND INDEX_NAME='uk_ticket_consume_idem'
 ORDER BY SEQ_IN_INDEX;

SELECT '校验C：存量数据未受影响（non_null_idempotency_rows 期望 0）' AS check_item;
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
           AND COLUMN_NAME='idempotency_key')                      AS column_ready,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
           AND INDEX_NAME='uk_ticket_consume_idem')                AS uk_cols,
       (SELECT COUNT(*) FROM ticket_record
         WHERE idempotency_key IS NOT NULL)                        AS non_null_idempotency_rows,
       (SELECT COUNT(*) FROM ticket_record)                        AS ticket_record_rows,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
           AND INDEX_NAME='uk_ticket_consume')                     AS legacy_uk_still_there;
-- ↑ 期望：column_ready=1, uk_cols=2, non_null_idempotency_rows=0,
--        legacy_uk_still_there=3（uk_ticket_consume 有 3 列，STATISTICS 一行一列，故是 3 不是 1）
