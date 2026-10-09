-- v77：拒付纠错/资产解冻及退款争议结案。只新增审计与状态表，不改原款/原判断/资产事实。
-- 先核准完整目标并备份；本文件仅准备，真实库执行另行授权。基线由统一收尾者合并。
CREATE TABLE IF NOT EXISTS customer_refusal_resolution (
  order_id BIGINT NOT NULL PRIMARY KEY,
  judgment_revoked TINYINT NOT NULL DEFAULT 0,
  asset_freeze_released TINYINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS customer_refusal_action (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  actor_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  action VARCHAR(24) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  from_version BIGINT NOT NULL,
  to_version BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_refusal_action (actor_key,order_id,idempotency_key),
  KEY idx_refusal_action_order (order_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS refund_dispute (
  refund_type VARCHAR(24) NOT NULL,
  refund_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  responsible_station_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  last_result VARCHAR(1000) NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (refund_type,refund_id),
  KEY idx_refund_dispute_station (responsible_station_id,status,update_time),
  KEY idx_refund_dispute_customer (customer_id,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS refund_dispute_action (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  refund_type VARCHAR(24) NOT NULL,
  refund_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  responsible_station_id BIGINT NOT NULL,
  actor_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  operator_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  from_version BIGINT NOT NULL,
  to_version BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_refund_dispute_action (actor_key,refund_type,refund_id,idempotency_key),
  KEY idx_refund_dispute_history (refund_type,refund_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
