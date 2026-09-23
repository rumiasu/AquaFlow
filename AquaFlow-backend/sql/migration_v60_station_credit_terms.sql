-- =============================================================================
-- v60 · 账期从「客户级」改成「客户 × 水站级」，并把企业默认账期做成可一键套用
--
-- 产品裁定（2026-09-21）：
--   · 「账期也改成站级，企业的话，我觉得跨站情景可能不多」—— 选站级；
--   · 「账期先依赖平台默认吧，按照企业主流流程来，点开启企业账户时可以一键使用，
--      然后后续可以重设」。
--
-- 为什么必须站级（不是洁癖，是租户隔离）：
--   `company_info.due_days` 是**客户级**、`uk_company_customer(customer_id)` 唯一，
--   而 `ManagerReceivableController.ownershipError` 只校验"该客户归属本站" ——
--   于是 A 站设的账期会在 B 站生效，B 站还能把 A 站设的值改掉。
--   仓库里同一件事早有定论（`customer_privilege` 表注释）：
--   「特权按 (customer, station) 隔离：A 站给的不在 B 站生效（否则等于跨站送钱）」。
--   `offline_payment_enabled`（"能不能不预付就下单"）本来就在 `customer_station_config`，
--   账期（"这笔钱最晚什么时候该到"）与它同层，粒度才一致。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 只加 2 列（1 个可空 int + 1 个可空 varchar(16)），**不动任何存量列的含义与任何金额**。
--   · ⚠️ **存量 `company_info.due_days` 自本迁移起不再被任何代码读取** ——
--     判据正本改为 `customer_station_config.due_days / settlement_cycle`
--     （`ReceivableService.resolveDueDate`）。效果等价于"账期一律清空、由站长重设"
--     （2026-09-21 用户已同意），但**不删数据**：老值留在原列里可回溯，
--     站长随时能查"这家公司以前是不是设过账期"。
--     → 升级后站长需要给真正要挂账的客户重新设一次（动作是"选一个档"，不是填数）。
--   · **不搬迁存量**是有意的：`company_info.due_days` 只记了值、没记"是哪个站设的"，
--     复制给所有归属站会给 B 站**凭空授出赊账权**。若某站确认要继承，用文末的可选 SQL 逐站执行。
--   · 判定与写入点（全部集中、可 grep）：
--     读 = `ReceivableService.resolveDueDate`（下单时快照进 `orders.due_date`，之后只读）；
--     连同"欠款即停"一起看 = `PaymentServiceImpl.offlinePaymentBlockReason`；
--     写 = `CustomerStationConfigMapper.updateCreditTerms`；
--     一键套用 = `EnterpriseIdentityService.review`（审核通过时）。
--   · 索引：不加。查询全部按 `(customer_id, station_id)` 命中既有唯一键 `uk_customer_station`。
--
-- 幂等
--   · 两列都是**先查 information_schema 再 PREPARE**，已存在则打印 skip 并原样放行；
--   · 本迁移**不含任何 UPDATE / DELETE / INSERT**，二次执行不会有副作用。
--
-- 回滚
--   · `ALTER TABLE customer_station_config DROP COLUMN due_days, DROP COLUMN settlement_cycle;`
--     再把 `ReceivableService.resolveDueDate` 改回读 `company_info.due_days`。
--   · 回滚**不丢钱、不丢订单**：这两列是判据用的配置，不是账。
--     ⚠️ 但回滚会丢掉"站长升级后重设过的站级账期"，需要重新设一次。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（关键表不在就中止，避免在别的库上瞎改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('customer_station_config','orders','customer'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（customer_station_config / orders / customer 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：该客户在该站的账期天数（NULL = 即时结清、不挂账）
--   ⚠️ 列注释必须与 schema.sql **逐字节一致**（README 用 HEX(COLUMN_COMMENT) 核对）
-- -----------------------------------------------------------------------------
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE customer_station_config ADD COLUMN due_days int DEFAULT NULL COMMENT ''该客户在该站的账期天数（NULL=即时结清不挂账）。2026-09-21 起账期从客户级 company_info.due_days 改为站级''',
    'SELECT ''skip: due_days 已存在''')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
    AND COLUMN_NAME = 'due_days');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 2 步：结算周期（决定"从哪天起算账期"）
--   IMMEDIATE = 现结（不写应付日期，与升级前 due_date 为空的行为一致）
--   MONTHLY   = 月结（从**当月最后一天**起算，企业主流）
-- -----------------------------------------------------------------------------
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE customer_station_config ADD COLUMN settlement_cycle varchar(16) DEFAULT NULL COMMENT ''结算周期：IMMEDIATE=现结 / MONTHLY=月结（从当月最后一天起算）。NULL 视为 IMMEDIATE''',
    'SELECT ''skip: settlement_cycle 已存在''')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
    AND COLUMN_NAME = 'settlement_cycle');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 3 步：核对（两列都在 = 2；列注释用 HEX 比对，避免控制台编码误导）
-- -----------------------------------------------------------------------------
SELECT CONCAT('列命中 ', COUNT(*), '/2（2 = 完成）') AS add_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
   AND COLUMN_NAME IN ('due_days','settlement_cycle');

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, HEX(COLUMN_COMMENT) AS comment_hex
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'customer_station_config'
   AND COLUMN_NAME IN ('due_days','settlement_cycle');

-- -----------------------------------------------------------------------------
-- 【可选·不自动执行】把某个站的历史账期从客户级继承过来
--
-- 只有在"该站确认这家客户以前就是自己给的账期"时才执行，**逐站、逐客户**做。
-- 全量自动继承是错的：company_info.due_days 没记"是谁设的"，复制给所有归属站
-- 等于给 B 站凭空授出赊账权。
--
--   UPDATE customer_station_config csc
--     JOIN company_info ci ON ci.customer_id = csc.customer_id
--      SET csc.due_days = ci.due_days,
--          csc.settlement_cycle = 'MONTHLY'
--    WHERE csc.station_id = <该站ID>
--      AND csc.offline_payment_enabled = 1      -- 只给"已经开了后付"的客户
--      AND ci.due_days IS NOT NULL AND ci.due_days > 0;
--
-- 执行前先 mysqldump 该库到 backup/（AGENTS.md §4）。
-- -----------------------------------------------------------------------------
