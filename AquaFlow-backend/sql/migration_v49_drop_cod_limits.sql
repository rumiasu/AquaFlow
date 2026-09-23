-- =============================================================================
-- v49 · 撤回货到付款的「首单是否放行」「单笔上限」两项配置（v48 加了、现在不做）
--
-- 产品裁定（2026-09-18 第四批）：「既然目前还由站长审核，首单是否放行（默认不给）、
-- 单笔上限（默认留空＝不限）这两个先不做了。」
-- 理由：货到付款本来就**只能由站长逐个客户开通**（默认关闭、没有批量开关），
-- 站长审核本身就是第一道闸；在它之上再加"首单/额度"两层，是重复设防。
--
-- 保留的是**欠款即停**：它不是新列，判据是"该客户在本站还有逾期未结的现金单"
-- （`payment_status = 1 且 status <> 5 且 payment_method = 2 且 due_date < 今天`），
-- 继续由 `PaymentServiceImpl.offlinePaymentBlockReason` 判定 —— 这条与站长审核不重复，
-- 它挡的是"已经欠着钱还想再赊"。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · DROP 2 列：`offline_payment_single_limit`、`offline_payment_allow_first_order`
--     （v48 刚加、语义只有这两处判据在读）。**不动任何存量列、任何金额、任何订单**。
--   · ⚠️ 本脚本自带护栏：**只要有一行的这两列不是默认值（single_limit 非空 或
--     allow_first_order ≠ 0），就中止**，不执行 DROP —— 那说明已经有人配过，
--     应当先人工确认（当初 v41 删列也是这个规矩）。
--   · 回滚：重新执行 v48 即可（它是幂等的纯加列）。
--
-- 幂等
--   · 先查 information_schema：列已不存在则打印 skip 并原样放行；
--   · 护栏查询是只读的，重复执行无副作用。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('customer_station_config','customer','orders'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（customer_station_config / customer / orders 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：护栏 —— 已有非默认配置就中止（说明有人在用，先人工确认再删）
--   两列都在时才查；任一列已被删掉，本步自动跳过（幂等重跑安全）
-- -----------------------------------------------------------------------------
SET @both_cols := (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
                     AND COLUMN_NAME IN ('offline_payment_single_limit','offline_payment_allow_first_order'));

SET @configured := 0;
SET @sql := IF(@both_cols = 2,
    'SELECT COUNT(*) INTO @configured FROM customer_station_config WHERE offline_payment_single_limit IS NOT NULL OR offline_payment_allow_first_order <> 0',
    'SELECT 0 INTO @configured');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

SELECT IF(@configured = 0,
          'OK: 没有客户配过这两项，可以安全删除',
          CONCAT('ABORT: 有 ', @configured, ' 行配过（单笔上限/首单放行），已终止 —— 请先确认这些配置不再需要')) AS guard;

SET @abort2 := IF(@configured <> 0, 'SELECT * FROM __ABORT_HAS_CONFIGURED_ROWS__', 'SELECT 1');
PREPARE st_abort2 FROM @abort2;
EXECUTE st_abort2;
DEALLOCATE PREPARE st_abort2;

-- -----------------------------------------------------------------------------
-- 第 2 步：删列（先判"两列都在"，避免半删状态下重复报错）
-- -----------------------------------------------------------------------------
SET @sql := IF(@both_cols = 2,
    'ALTER TABLE customer_station_config DROP COLUMN offline_payment_single_limit, DROP COLUMN offline_payment_allow_first_order',
    'SELECT ''skip: 两列已不存在（或只剩一列，请人工核对）''');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 3 步：核对（0 = 已清理干净）
-- -----------------------------------------------------------------------------
SELECT CONCAT('残留列 ', COUNT(*), ' 个（0 = 完成）') AS drop_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
   AND COLUMN_NAME IN ('offline_payment_single_limit','offline_payment_allow_first_order');

SELECT offline_payment_enabled, COUNT(*) AS rows_cnt
  FROM customer_station_config GROUP BY offline_payment_enabled;
