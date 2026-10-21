-- C08原成功时间与未知原渠道请求保全；不回填旧时间、不连接商户或执行交易。
SET @af_c08_base=(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('payment_record','barrel_return_payout','consumption_refund','barrel_purchase_refund','order_barrel_refund'));
SET @af_c08_check=IF(@af_c08_base=5,'SELECT 1','SELECT * FROM __ABORT_C08_WRONG_DATABASE_OR_MISSING_BASELINE__');
PREPARE af_c08_pre FROM @af_c08_check;
EXECUTE af_c08_pre;
DEALLOCATE PREPARE af_c08_pre;
CREATE TABLE IF NOT EXISTS payment_original_success (
 payment_id bigint NOT NULL, customer_id bigint NOT NULL, station_id bigint NOT NULL, order_id bigint DEFAULT NULL,
 amount decimal(10,2) NOT NULL, payer_total_cents int NOT NULL,
 binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 transaction_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 success_epoch_second bigint NOT NULL, success_nano int NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(payment_id), UNIQUE KEY uk_original_success_tx(binding_digest,transaction_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS payment_original_refund_hold (
 payment_id bigint NOT NULL, request_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_reference varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 original_transaction_reference varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 original_amount decimal(10,2) NOT NULL, amount decimal(10,2) NOT NULL,
 currency char(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(payment_id,request_key), UNIQUE KEY uk_original_refund_request(binding_digest,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @af_c08_ddl=IF(NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='barrel_return_payout' AND column_name='recorded_epoch_second'),'ALTER TABLE barrel_return_payout ADD recorded_epoch_second bigint DEFAULT NULL','SELECT 1');
PREPARE af_c08_st FROM @af_c08_ddl;
EXECUTE af_c08_st;
DEALLOCATE PREPARE af_c08_st;
SET @af_c08_ddl=IF(NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='barrel_return_payout' AND column_name='recorded_nano'),'ALTER TABLE barrel_return_payout ADD recorded_nano int DEFAULT NULL','SELECT 1');
PREPARE af_c08_st FROM @af_c08_ddl;
EXECUTE af_c08_st;
DEALLOCATE PREPARE af_c08_st;
SELECT 'AQUAFLOW_C08_V83_STRUCTURE_INSTALLED' AS completion;
