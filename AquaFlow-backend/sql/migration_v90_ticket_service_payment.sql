-- v90: ticket water + separately collected service fees; no historical money rewrite.
-- Install after verifying target and recoverable backup. CREATE only; no deletion/seed/payment.
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
CREATE TABLE IF NOT EXISTS order_ticket_hold (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL, lot_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 quantity INT NOT NULL, unit_price DECIMAL(12,4) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'HELD',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_order_ticket_lot(order_id,lot_id),
 KEY idx_ticket_hold_lot(lot_id,state), KEY idx_ticket_hold_order(order_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ticket_delivery_offer (
 station_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 tier_kind VARCHAR(16) NOT NULL, tier_key BIGINT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(station_id,product_id,tier_kind,tier_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
