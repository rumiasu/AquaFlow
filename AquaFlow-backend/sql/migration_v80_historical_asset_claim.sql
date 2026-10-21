-- C-13：客户提交时已确认，站长通过时同事务绑定/期初入账。
-- 只建旁表，不搬真实数据、不创建订单/付款、不自动运行。
CREATE TABLE IF NOT EXISTS historical_asset_claim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  client_token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_digest VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_json JSON NOT NULL,
  status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  reviewer_id BIGINT NULL,
  review_note VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  review_time DATETIME NULL,
  active_customer_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status IN ('PENDING','APPROVED') THEN customer_id ELSE NULL END) STORED,
  PRIMARY KEY(id),
  UNIQUE KEY uk_asset_claim_token(customer_id,client_token),
  UNIQUE KEY uk_asset_claim_once(station_id,active_customer_id),
  KEY idx_asset_claim_pending(station_id,status,id),
  CONSTRAINT ck_asset_claim_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  CONSTRAINT ck_asset_claim_review CHECK
    ((status='PENDING' AND reviewer_id IS NULL AND review_time IS NULL)
     OR (status<>'PENDING' AND reviewer_id IS NOT NULL AND review_time IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
