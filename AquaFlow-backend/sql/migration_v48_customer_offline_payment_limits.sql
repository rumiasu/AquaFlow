-- =============================================================================
-- v48 · 货到付款（现金）的**客户级约束**
--
-- 产品裁定（2026-09-18）：「货到付款…如果做也要对首单和大额订单设限（特殊允许的客户可以大额）」，
-- 并且这些约束「最好是给站长定，在设置是否允许货到付款时就给弹出来」。
-- 于是把「谁能货到付款」从**只有开关**细化为站长可配的三件事：
--
--   ① 单笔上限 `offline_payment_single_limit`（NULL = 不限）—— "特殊允许的客户可以大额"
--      就是把这列设成 NULL 或一个更大的数；
--   ② 首单是否放行 `offline_payment_allow_first_order`（**默认 0 = 不放行**）——
--      新客户第一单必须先走水票/在线付（与"首单收满押金"同一个逻辑：先建立信用）；
--   ③ **欠款即停**不是新列：判据是"该客户在本站还有逾期未结的现金单"
--      （`payment_status = 1 且 status <> 5 且 due_date < 今天`），用现有列现算，不发明新规则。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 只加 2 列：1 个可空 decimal(10,2) + 1 个 NOT NULL DEFAULT 0 的 tinyint。
--     **不动任何存量列的含义与任何金额**。
--   · 存量客户的读法：`single_limit = NULL` → 不限（与升级前行为一致）；
--     `allow_first_order = 0` → 首单不放行 —— 这是**新引入的约束**（升级前只有开关一层），
--     所以升级后站长需要在界面上给"已经合作过、但系统里还没订单的老客户"逐个放开。
--     这是产品要的效果（首单不给货到付款），不是数据问题。
--   · 判定与写入点（全部集中、可 grep）：`PaymentServiceImpl.offlinePaymentBlockReason` 是**唯一判据**，
--     下单（`OrderServiceImpl.createOrder` 现金分支）与报价（`PaymentServiceImpl.quote`，
--     决定"货到付款"这个选项可不可选）都调它；配置写入走
--     `CustomerStationConfigMapper.updateOfflinePaymentConfig`。
--   · 索引：不加。查询全部按 `(customer_id, station_id)` 命中主键/既有唯一键。
--
-- 幂等
--   · 两列都是**先查 information_schema 再 PREPARE**，已存在则打印 skip 并原样放行；
--   · 本迁移不含任何 UPDATE/DELETE，二次执行不会有副作用。
--
-- 回滚
--   · `ALTER TABLE customer_station_config DROP COLUMN offline_payment_single_limit,
--      DROP COLUMN offline_payment_allow_first_order;`
--   · 回滚**只是回到"只有开关一层"**，不丢钱、不丢订单：这两列是判据用的配置，不是账。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（关键表不在就中止，避免在别的库上瞎改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('customer_station_config','customer','station'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（customer_station_config / customer / station 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：单笔上限（可空 = 不限，用于"特殊客户放宽大额"）
--   ⚠️ 列注释必须与 schema.sql **逐字节一致**（README 用 HEX(COLUMN_COMMENT) 核对）
-- -----------------------------------------------------------------------------
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE customer_station_config ADD COLUMN offline_payment_single_limit decimal(10,2) DEFAULT NULL COMMENT ''货到付款单笔上限（NULL=不限，特殊客户可放宽）''',
    'SELECT ''skip: offline_payment_single_limit 已存在''')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
    AND COLUMN_NAME = 'offline_payment_single_limit');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 2 步：首单是否放行（默认 0 = 不放行）
-- -----------------------------------------------------------------------------
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE customer_station_config ADD COLUMN offline_payment_allow_first_order tinyint NOT NULL DEFAULT 0 COMMENT ''是否允许首单货到付款（0=不允许，默认）''',
    'SELECT ''skip: offline_payment_allow_first_order 已存在''')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
    AND COLUMN_NAME = 'offline_payment_allow_first_order');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 3 步：核对（两列都在 = 1；列注释用 HEX 比对，避免控制台编码误导）
-- -----------------------------------------------------------------------------
SELECT CONCAT('列命中 ', COUNT(*), '/2（2 = 完成）') AS add_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
   AND COLUMN_NAME IN ('offline_payment_single_limit','offline_payment_allow_first_order');

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, HEX(COLUMN_COMMENT) AS comment_hex
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
   AND COLUMN_NAME IN ('offline_payment_single_limit','offline_payment_allow_first_order');
