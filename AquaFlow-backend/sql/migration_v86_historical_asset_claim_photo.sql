-- 可选认领凭证：先按客户与申请凭据保存，申请提交后由同一凭据关联。手工执行，不迁移业务数据。
CREATE TABLE IF NOT EXISTS historical_asset_claim_photo (
  customer_id BIGINT NOT NULL,
  station_id BIGINT NOT NULL,
  client_token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  object_name VARCHAR(512) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id, client_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
