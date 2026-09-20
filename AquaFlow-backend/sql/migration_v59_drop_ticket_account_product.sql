-- =============================================================================
-- V59: 撤回 v54 的 `ticket_record.account_product_id`（**破坏性 DROP**）
-- =============================================================================
-- ⚠️ 本脚本是**破坏性**的（DROP COLUMN）。按仓库规程：
--   ① 先上代码（代码不再读写这一列）→ ② 再执行本脚本；执行前 `mysqldump` 到 `backup/`。
--   代码侧已于 2026-09-20 改为不写不读（见 `TicketAccountServiceImpl` / `TicketRecordMapper`）。
--
-- -----------------------------------------------------------------------------
-- 为什么撤回（同 v58 的文件头：产品 2026-09-20 澄清了统一水票的形态）
--   v54 加这一列，是为了区分"这一笔水票变动落在哪个账户"——当时把统一水票理解成
--   **站级通用票账户（product_id = 0）**，于是同一张订单里"订单行商品"与"扣票账户"会不同，
--   需要额外一列自证（否则 `uk_ticket_consume(order_id, product_id, source)` 的逐项幂等
--   会把同单第二个走统一票的商品当成重复而静默跳过）。
--
--   产品 2026-09-20 澄清 + 拍板：**统一水票不是一种票，而是站级的一套折扣规则**
--   （按各款水自己的价打折），买来的票进**该商品**的账户 —— 于是"账户商品 id"与
--   "订单行商品"**恒等**，这一列不再承载任何信息，留着就是一张永远等于 product_id 的死列。
--
-- 安全前提（已逐条核实，2026-09-20）
--   · 真实库 `ticket_record` **0 行** → 不可能有值可丢；
--   · `aquaflow_test` 由 `sql/schema.sql` 重建，基线里已同步去掉该列。
--   · 脚本仍自带护栏：**只要有一行 account_product_id 非 NULL 就中止**，不执行 DROP。
--
-- 幂等：可重复执行（查 information_schema.COLUMNS）。
-- 回滚：重新执行 `migration_v54_ticket_record_account.sql`（纯加列）。
-- =============================================================================

SET @db := DATABASE();

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
                   AND COLUMN_NAME='account_product_id');

-- 护栏：列内有值就中止（那时说明形态收口还没做完，DROP 会丢信息）
SET @has_value := IF(@has_col>0,
  (SELECT COUNT(*) FROM ticket_record WHERE account_product_id IS NOT NULL), 0);

SELECT @has_col AS column_exists, @has_value AS rows_with_value,
       IF(@has_col=0, 'skip: 列本就不存在',
          IF(@has_value>0, 'ABORT: 列内有值，拒绝 DROP（请先确认这些流水怎么处置）',
             'ok: 可以安全 DROP')) AS verdict;

SET @s := IF(@has_col>0 AND @has_value=0,
  "ALTER TABLE ticket_record DROP COLUMN account_product_id",
  "SELECT '未执行 DROP（列不存在或列内有值）' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 自检
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
      AND COLUMN_NAME='account_product_id') AS column_left,
  (SELECT COUNT(*) FROM ticket_record) AS total_records;
