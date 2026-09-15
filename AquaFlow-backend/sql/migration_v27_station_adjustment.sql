-- =============================================================================
-- V27: 站长资产调整单（station_adjustment）+ 流水表关联列 + 对账结果表
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v27_station_adjustment.sql
--
-- -----------------------------------------------------------------------------
-- 问题
--   1) 站长需要为历史客户「人工补录」桶权益 / 押金 / 水票，但系统里没有承载
--      「谁、何时、为什么、改了前后的什么值」的单据。旧实现只能直接改余额
--      （BarrelAssetServiceImpl 直写 customer_barrel_asset），既绕过唯一真相源
--      customer_barrel_lot，也留不下可追查的凭据。
--   2) 三张流水表（barrel_record / deposit_record / ticket_record）没有任何列能把
--      「这条流水属于哪张调整单」表达出来，因而无法做「一张单只生效一次」的数据库级兜底：
--        · deposit_record 此前【完全没有唯一键】，幂等只靠应用层；
--        · ticket_record 的唯一键是 (order_id, product_id, source)，而调整场景
--          order_id 为 NULL，MySQL 唯一键中 NULL 互不冲突 → 该键对调整记录零保护。
--   3) 对账结果（ReconciliationService）此前只写日志，无留痕、无查询入口。
--
-- 不改会怎样
--   补录与订正只能继续走「直写余额」，桶账恒等式「占用 = 权益 + over」会被破坏
--   （客户补录后 rightQty() 仍为 0，退桶被拒、押金退不出），且重复提交无法在数据库层拦住。
--
-- 影响面
--   · 纯新增：2 张新表 + 3 个可空列 + 3 个唯一键；不改任何既有列的类型、不动既有数据；
--   · 新列 adjustment_id 默认 NULL，既有流水行不受影响；
--   · 唯一键对 NULL 不生效，因此不会与存量数据冲突；
--   · 表量级小，MySQL 8 的 ONLINE DDL 加列/加索引不阻塞读写。
--
-- 幂等：可重复执行。建表用 IF NOT EXISTS；加列查 information_schema.COLUMNS；
--       加索引查 information_schema.STATISTICS。
-- 回滚：
--   本迁移为纯新增，业务回滚只需停用接口，不必删表/删列。
--   若确需物理回滚（会丢失调整单历史，慎用）：
--     ALTER TABLE barrel_record  DROP INDEX uk_record_adjustment,  DROP COLUMN adjustment_id;
--     ALTER TABLE deposit_record DROP INDEX uk_deposit_adjustment, DROP COLUMN adjustment_id;
--     ALTER TABLE ticket_record  DROP INDEX uk_ticket_adjustment,  DROP COLUMN adjustment_id;
--     DROP TABLE station_adjustment;
--     DROP TABLE reconciliation_result;
--
-- 相关：设计书 docs/design/10-站长资产调整单.md
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_station := (SELECT COUNT(*) FROM information_schema.TABLES
                     WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station');
SET @s := IF(@has_station>0,
  "SELECT '开始执行 V27（站长资产调整单）' AS note",
  "SELECT 'ABORT: 当前库没有 station 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 1) 单据头表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `station_adjustment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `adjust_no` varchar(32) NOT NULL COMMENT '单据号 ADJyyyymmdd-000001',
  `station_id` bigint NOT NULL COMMENT '发起站=资产所属站；跨站一律拒绝',
  `customer_id` bigint NOT NULL,
  `product_id` bigint DEFAULT NULL COMMENT '桶类调整必填',
  `adjust_type` varchar(32) NOT NULL COMMENT 'BARREL_GRANT/BARREL_REVOKE/OVER_ADJUST/DEPOSIT_GRANT/DEPOSIT_DEDUCT/TICKET_GRANT/TICKET_DEDUCT',
  `qty` int DEFAULT NULL COMMENT '桶/水票数量（绝对值，方向由 adjust_type 决定）',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '押金金额（正数，方向由 adjust_type 决定）',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '补录单价快照（桶权益用）',
  `price_source` tinyint DEFAULT NULL COMMENT '1订单实付 2当时商品押金 3当前商品押金(推断)',
  `is_migrated` tinyint NOT NULL DEFAULT '0' COMMENT '1=历史迁移（单价为推断，退款需二次确认）',
  `reason` varchar(200) NOT NULL COMMENT '调整原因（必填）',
  `evidence` varchar(500) DEFAULT NULL COMMENT '证据图 objectName，逗号分隔',
  `before_snapshot` varchar(500) DEFAULT NULL COMMENT '执行前快照 JSON',
  `after_snapshot` varchar(500) DEFAULT NULL COMMENT '执行后快照 JSON',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EFFECTIVE/REVERSED/REJECTED',
  `client_token` varchar(64) NOT NULL COMMENT '客户端幂等键',
  `operator_id` bigint NOT NULL COMMENT '发起人（站长）',
  `executor_id` bigint DEFAULT NULL COMMENT '执行人',
  `reverses` bigint DEFAULT NULL COMMENT '本单是反冲哪张单',
  `reversed_by` bigint DEFAULT NULL COMMENT '本单被哪张单反冲',
  `execute_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adjust_no` (`adjust_no`),
  UNIQUE KEY `uk_adjust_client_token` (`client_token`),
  KEY `idx_adjust_station_time` (`station_id`,`create_time`),
  KEY `idx_adjust_customer` (`customer_id`,`station_id`),
  KEY `idx_adjust_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站长资产调整单：人工补录/订正的唯一合法来源';

-- -----------------------------------------------------------------------------
-- 2) 对账结果表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reconciliation_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_date` date NOT NULL COMMENT '对账执行日',
  `check_key` varchar(64) NOT NULL COMMENT '检查项键（E3_rightVsLot / E5_physicalConservation 等）',
  `diff_count` int NOT NULL DEFAULT '0' COMMENT '不平条数',
  `level` varchar(8) NOT NULL DEFAULT 'ERROR' COMMENT 'ERROR/WARN',
  `sample_ids` varchar(500) DEFAULT NULL COMMENT '样本 id，供人工追查',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recon_run_check` (`run_date`,`check_key`),
  KEY `idx_recon_date` (`run_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账结果：每日每检查项一行';

-- -----------------------------------------------------------------------------
-- 3) 三张流水表加关联列（逐个预检，已存在则跳过）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='adjustment_id')=0,
  "ALTER TABLE barrel_record ADD COLUMN adjustment_id bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生' AFTER returned_qty",
  "SELECT 'skip: barrel_record.adjustment_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record' AND COLUMN_NAME='adjustment_id')=0,
  "ALTER TABLE deposit_record ADD COLUMN adjustment_id bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生' AFTER create_time",
  "SELECT 'skip: deposit_record.adjustment_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record' AND COLUMN_NAME='adjustment_id')=0,
  "ALTER TABLE ticket_record ADD COLUMN adjustment_id bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生' AFTER station_id",
  "SELECT 'skip: ticket_record.adjustment_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 4) 调整场景专属唯一键（一张单最多一条对应流水）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND INDEX_NAME='uk_record_adjustment')=0,
  "ALTER TABLE barrel_record ADD UNIQUE KEY uk_record_adjustment (adjustment_id)",
  "SELECT 'skip: uk_record_adjustment 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record' AND INDEX_NAME='uk_deposit_adjustment')=0,
  "ALTER TABLE deposit_record ADD UNIQUE KEY uk_deposit_adjustment (adjustment_id)",
  "SELECT 'skip: uk_deposit_adjustment 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record' AND INDEX_NAME='uk_ticket_adjustment')=0,
  "ALTER TABLE ticket_record ADD UNIQUE KEY uk_ticket_adjustment (adjustment_id, product_id, source)",
  "SELECT 'skip: uk_ticket_adjustment 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 5) 校验
-- -----------------------------------------------------------------------------
SELECT '校验A：新表应存在（station_adjustment / reconciliation_result）' AS check_item;
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('station_adjustment','reconciliation_result');

SELECT '校验B：三张流水表的 adjustment_id 列应存在' AS check_item;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND COLUMN_NAME='adjustment_id'
   AND TABLE_NAME IN ('barrel_record','deposit_record','ticket_record');

SELECT '校验C：三个唯一键应存在' AS check_item;
SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND INDEX_NAME IN
       ('uk_record_adjustment','uk_deposit_adjustment','uk_ticket_adjustment')
 GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE;

SELECT '校验D：存量数据行数（新迁移不应改变任何行数）' AS check_item;
SELECT (SELECT COUNT(*) FROM barrel_record)  AS barrel_record_rows,
       (SELECT COUNT(*) FROM deposit_record) AS deposit_record_rows,
       (SELECT COUNT(*) FROM ticket_record)  AS ticket_record_rows;
