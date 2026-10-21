-- C04: 原消费实退凭据逐笔责任协商；不改原款、结清或客户资产，不执行转款。
-- 先核准目标、可恢复备份与v81，再装本结构及匹配代码；新库schema已吸收。
SET @af_c04_base=(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('orders','consumption_refund','payment_record','inter_station_settlement','inter_station_recovery'));
SET @af_c04_check=IF(@af_c04_base=5,'SELECT 1','SELECT * FROM __ABORT_C04_WRONG_DATABASE_OR_MISSING_BASELINE__');
PREPARE af_c04_pre FROM @af_c04_check;
EXECUTE af_c04_pre;
DEALLOCATE PREPARE af_c04_pre;

CREATE TABLE IF NOT EXISTS station_aftercare (
  refund_payment_id bigint NOT NULL, order_id bigint NOT NULL,
  refund_station_id bigint NOT NULL, other_station_id bigint NOT NULL,
  refund_amount decimal(10,2) NOT NULL, proposed_amount decimal(10,2) NOT NULL,
  proposed_station_id bigint NOT NULL, proposed_by bigint NOT NULL,
  reason varchar(1000) NOT NULL, status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  agreed_by bigint DEFAULT NULL, agreed_time datetime DEFAULT NULL,
  paid_by bigint DEFAULT NULL, paid_time datetime DEFAULT NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(refund_payment_id), KEY idx_aftercare_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS station_aftercare_action (
  id bigint NOT NULL AUTO_INCREMENT, refund_payment_id bigint NOT NULL,
  actor_key varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount decimal(10,2) NOT NULL, reason varchar(1000) NOT NULL,
  from_version bigint NOT NULL, to_version bigint NOT NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_aftercare_action(refund_payment_id,actor_key,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SELECT 'AQUAFLOW_C04_V82_STRUCTURE_INSTALLED' AS completion;
