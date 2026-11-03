-- v90 single-statement console alternative; identical to the corresponding master migration statement.
-- Confirm target and backup first. CREATE only; no data rewrite.
CREATE TABLE IF NOT EXISTS ticket_delivery_benefit (
 payment_id BIGINT NOT NULL PRIMARY KEY,
 customer_id BIGINT NOT NULL, station_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 tier_kind VARCHAR(16) NOT NULL, tier_key BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 order_id BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_delivery_benefit_owner(customer_id,station_id,product_id,state),
 KEY idx_delivery_benefit_order(order_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
