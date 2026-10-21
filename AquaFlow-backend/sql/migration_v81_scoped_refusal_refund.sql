-- v81: C03争议范围与分次实退凭据。先核准目标/备份，结构先于一致代码；不自动改历史案件/钱/桶。
-- legacy_return_record_id 保留旧“同一购买+退还单只退一次”约束；新分次记录另受payout_id唯一键约束。
-- 不凭此启用真实渠道；实际现金登记与剩余待退分列，不清债、不增审批或客户确认。
SET @af_c03_base = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('customer_refusal_case','customer_refusal_resolution','customer_refusal_action','barrel_return_detail','barrel_return_lot_hold','barrel_purchase_refund','order_barrel_refund','customer_deposit_account'));
SET @af_c03_precheck = IF(@af_c03_base=8,'SELECT 1','SELECT * FROM __ABORT_C03_WRONG_DATABASE_OR_MISSING_BASELINE__');
PREPARE af_c03_pre FROM @af_c03_precheck;
EXECUTE af_c03_pre;
DEALLOCATE PREPARE af_c03_pre;

CREATE TABLE IF NOT EXISTS customer_refusal_freeze_scope (
  order_id bigint NOT NULL, product_id bigint NOT NULL,
  amount decimal(10,2) NOT NULL,
  PRIMARY KEY(order_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS barrel_return_payout (
  id bigint NOT NULL AUTO_INCREMENT, record_id bigint NOT NULL,
  actor_key varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount decimal(10,2) NOT NULL, payer_id bigint NOT NULL,
  final_payout tinyint NOT NULL DEFAULT 0, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_return_payout_key(record_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='barrel_purchase_refund' AND COLUMN_NAME='payout_id'), 'ALTER TABLE barrel_purchase_refund ADD COLUMN payout_id bigint DEFAULT NULL', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='barrel_purchase_refund' AND COLUMN_NAME='legacy_return_record_id'), 'ALTER TABLE barrel_purchase_refund ADD COLUMN legacy_return_record_id bigint GENERATED ALWAYS AS (IF(payout_id IS NULL,return_record_id,NULL)) STORED', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='barrel_purchase_refund' AND INDEX_NAME='uk_purchase_return' AND COLUMN_NAME='return_record_id'), 'ALTER TABLE barrel_purchase_refund DROP INDEX uk_purchase_return', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='barrel_purchase_refund' AND INDEX_NAME='uk_purchase_return'), 'ALTER TABLE barrel_purchase_refund ADD UNIQUE KEY uk_purchase_return(purchase_id,legacy_return_record_id)', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='barrel_purchase_refund' AND INDEX_NAME='uk_return_payout_source'), 'ALTER TABLE barrel_purchase_refund ADD UNIQUE KEY uk_return_payout_source(purchase_id,return_record_id,payout_id)', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_barrel_refund' AND COLUMN_NAME='payout_id'), 'ALTER TABLE order_barrel_refund ADD COLUMN payout_id bigint DEFAULT NULL', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_barrel_refund' AND COLUMN_NAME='legacy_return_record_id'), 'ALTER TABLE order_barrel_refund ADD COLUMN legacy_return_record_id bigint GENERATED ALWAYS AS (IF(payout_id IS NULL,return_record_id,NULL)) STORED', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_barrel_refund' AND INDEX_NAME='uk_order_barrel_return' AND COLUMN_NAME='return_record_id'), 'ALTER TABLE order_barrel_refund DROP INDEX uk_order_barrel_return', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_barrel_refund' AND INDEX_NAME='uk_order_barrel_return'), 'ALTER TABLE order_barrel_refund ADD UNIQUE KEY uk_order_barrel_return(purchase_id,legacy_return_record_id)', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_barrel_refund' AND INDEX_NAME='uk_return_payout_source'), 'ALTER TABLE order_barrel_refund ADD UNIQUE KEY uk_return_payout_source(purchase_id,return_record_id,payout_id)', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;



SET @af_c03_ddl = IF(NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer_refusal_action' AND COLUMN_NAME='scope_json'), 'ALTER TABLE customer_refusal_action ADD COLUMN scope_json longtext DEFAULT NULL', 'SELECT 1');
PREPARE af_c03_st FROM @af_c03_ddl;
EXECUTE af_c03_st;
DEALLOCATE PREPARE af_c03_st;

SELECT 'AQUAFLOW_C03_V81_STRUCTURE_INSTALLED' AS completion;
