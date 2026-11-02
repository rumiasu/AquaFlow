-- Append-only non-financial exception resolution and customer-visible feedback replies.
-- No existing rows are changed. Check target and table absence/definition before execution.
CREATE TABLE IF NOT EXISTS case_handling_action (
 id bigint NOT NULL AUTO_INCREMENT,
 kind varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 case_id bigint NOT NULL,
 station_id bigint NOT NULL,
 actor_id bigint NOT NULL,
 version bigint NOT NULL,
 action varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason varchar(1000) NOT NULL,
 idempotency_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_digest varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),
 UNIQUE KEY uk_case_handling_version(kind,case_id,station_id,version),
 UNIQUE KEY uk_case_handling_request(kind,case_id,station_id,actor_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
