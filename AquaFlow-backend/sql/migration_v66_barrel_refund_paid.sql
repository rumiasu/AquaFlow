-- =============================================================================
-- v66 · 退押金「实际交付」两列（`barrel_record.refund_paid_time` / `refund_paid_by`）
--
-- 背景（正本 `docs/design/35-退押金实际交付-决策件.md`，2026-09-27 拍板）：
--   `doRefund`（`BarrelServiceImpl:605-669`）把押金**核销**记全了（批次 FIFO、负流水、状态 3），
--   但**没有任何字段**记录「这笔钱什么时候真的交到顾客手上、经谁的手」：
--     · `deposit_record.operator_id` = 核销这笔账的人（站长点的那个），不是交钱的人；
--     · `barrel_record.handle_time`  = 审批时间，不等于交钱时间。
--   于是「站长当场把 50 元现金给了顾客」与「桶收了、账上显示已退、钱还没给」
--   在系统里**完全同形**。顾客来问"押金退了吗"，系统会答"已退"，而对账是平的
--   —— **账平 ≠ 钱到手**，违反本轮验收标准「每一笔钱知道谁收、谁欠谁、什么时候算办完」。
--
-- 拍板口径（三条，见 `docs/design/35` §7）：
--   ① 采纳「加两列 + 交付确认」，**核销的人与交钱的人可以是两个**（站长核销、配送员下次上门代交），
--      所以**不复用** `operator_id`；
--   ② **不现场给钱的不要退** ⇒ 核销与交付必须在**同一次操作**里完成：
--      `status = 3` 且 `refund_paid_time IS NULL` = **违规数据**（先核销未交付），
--      站长端据此筛出"没给钱就先核销"的单；本迁移**不回填、不收紧**，NULL 是有意义的；
--   ③ 押金流水仍在核销那一步写（**不挪到交付那一步**）—— 一挪，对账等式 1
--      （`balance == SUM(deposit_record.amount)`）会在中间态不平。
--
-- 影响面：**纯加两个可空列**，无回填、无 UPDATE/DELETE、不改任何既有列含义。
--   存量行 `refund_paid_time` 全为 NULL —— 那是**事实**（升级前系统确实没记过交付），
--   **不要**用 `handle_time` 批量回填冒充交付时间（那等于把"没记"改成"记了"，
--   正是本迁移要消灭的那种混淆）。
--
-- 执行方式：与 v63/v65 相同 —— 出错即停止的客户端（mysql CLI 默认，**不要 `--force`**；
--   实测 `--force` 下退出码仍是 0，会骗人）。本脚本只有 DDL，可重复执行（information_schema 预检 + PREPARE）。
--
-- 回滚：`ALTER TABLE barrel_record DROP COLUMN refund_paid_time, DROP COLUMN refund_paid_by;`
--   ⚠️ 若代码已上线，**先回代码再删列**（顺序同 v41/v59）。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检
-- -----------------------------------------------------------------------------
SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'barrel_record');
SELECT IF(@tbl_exists = 1,
          'OK: barrel_record 存在（本脚本给它补 refund_paid_time / refund_paid_by）',
          'ABORT: 本库没有 barrel_record —— 先跑 schema.sql 或 v1 基线') AS precheck;

SET @abort := IF(@tbl_exists <> 1,
                 'SELECT * FROM __ABORT_V66_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加列（可空；已存在则跳过 —— 幂等）
-- -----------------------------------------------------------------------------
SET @has_time := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'barrel_record'
                    AND COLUMN_NAME = 'refund_paid_time');
SET @sql_add_time := IF(@has_time = 0,
    'ALTER TABLE barrel_record ADD COLUMN refund_paid_time datetime NULL COMMENT ''押金**实际交付**给顾客的时间(v66); 与 handle_time(审批) 是两件事; NULL 且 status=3 = 违规数据(先核销未交付)'' AFTER adjustment_id',
    'SELECT ''skip: refund_paid_time 已存在'' AS note');
PREPARE st1 FROM @sql_add_time;
EXECUTE st1;
DEALLOCATE PREPARE st1;

SET @has_by := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'barrel_record'
                  AND COLUMN_NAME = 'refund_paid_by');
SET @sql_add_by := IF(@has_by = 0,
    'ALTER TABLE barrel_record ADD COLUMN refund_paid_by bigint NULL COMMENT ''把押金交到顾客手上的人(staff.id, v66); 可以与 operator_id(核销人)不同 —— 站长核销、配送员代交'' AFTER refund_paid_time',
    'SELECT ''skip: refund_paid_by 已存在'' AS note');
PREPARE st2 FROM @sql_add_by;
EXECUTE st2;
DEALLOCATE PREPARE st2;

-- -----------------------------------------------------------------------------
-- 第 2 步：自查（**期望列数 = 2、违规行数 = 0**；违规行只可能是"代码先上了、这里没上"）
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'barrel_record'
           AND COLUMN_NAME IN ('refund_paid_time','refund_paid_by'))        AS paid_columns,
       (SELECT COUNT(*) FROM barrel_record WHERE status = 3)                AS refunded_rows,
       (SELECT COUNT(*) FROM barrel_record
         WHERE status = 3 AND refund_paid_time IS NULL)                     AS refunded_without_delivery;
-- ↑ 期望：paid_columns = 2；refunded_without_delivery = refunded_rows
--   （升级前退过的历史单**必然**没有交付时间 —— 那是事实，不是错误；
--     它从此变成一条可查的"历史欠账"，站长端只读计数会把它列出来）
