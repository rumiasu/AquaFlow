-- v76 reserved 2026-10-08: agreement evidence and own-account request preparation.
-- Additive only. No formal document activation, backfill, data deletion or credential change.
-- Install reviewed code and this schema before enabling any request intake.
CREATE TABLE IF NOT EXISTS agreement_document (
  version_id VARCHAR(90) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience VARCHAR(16) NOT NULL, document_type VARCHAR(16) NOT NULL,
  content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body_json LONGTEXT NOT NULL, create_time DATETIME NOT NULL,
  PRIMARY KEY(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agreement_acknowledgement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_type VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL,
  version_id VARCHAR(90) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(48) NOT NULL, action_source VARCHAR(24) NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_agreement_actor_version(actor_type,actor_id,version_id,event_type),
  KEY idx_agreement_actor(actor_type,actor_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS account_data_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_type VARCHAR(16) NOT NULL, actor_id BIGINT NOT NULL,
  request_type VARCHAR(16) NOT NULL, note VARCHAR(500) NOT NULL DEFAULT '',
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED', create_time DATETIME NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_account_request_idem(actor_type,actor_id,idempotency_key),
  KEY idx_account_request_actor(actor_type,actor_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
