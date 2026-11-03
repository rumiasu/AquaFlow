-- v90 single-statement console alternative; identical to the corresponding master migration statement.
-- Confirm target and backup first. CREATE only; no data rewrite.
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
