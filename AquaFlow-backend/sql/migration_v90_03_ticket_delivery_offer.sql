-- v90 single-statement console alternative; identical to the corresponding master migration statement.
-- Confirm target and backup first. CREATE only; no data rewrite.
CREATE TABLE IF NOT EXISTS ticket_delivery_offer (
 station_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 tier_kind VARCHAR(16) NOT NULL, tier_key BIGINT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(station_id,product_id,tier_kind,tier_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
