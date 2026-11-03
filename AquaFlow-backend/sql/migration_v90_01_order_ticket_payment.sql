-- v90 single-statement console alternative; identical to the corresponding master migration statement.
-- Confirm target and backup first. CREATE only; no data rewrite.
CREATE TABLE IF NOT EXISTS order_ticket_payment (
 order_id BIGINT NOT NULL PRIMARY KEY,
 customer_id BIGINT NOT NULL, station_id BIGINT NOT NULL,
 water_amount DECIMAL(12,2) NOT NULL, delivery_fee DECIMAL(12,2) NOT NULL,
 floor_fee DECIMAL(12,2) NOT NULL, delivery_credit DECIMAL(12,2) NOT NULL DEFAULT 0,
 deposit_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 cash_due DECIMAL(12,2) NOT NULL, cash_method INT NULL,
 voucher_payment_id BIGINT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'HELD',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 paid_time DATETIME NULL, cancel_time DATETIME NULL,
 KEY idx_ticket_payment_owner(customer_id,station_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
