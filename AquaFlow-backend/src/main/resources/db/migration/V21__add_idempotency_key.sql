-- Add idempotency_key to orders table for duplicate prevention
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64) DEFAULT NULL COMMENT '幂等键：防止重复下单';
CREATE UNIQUE INDEX idx_orders_idempotency_key ON orders(idempotency_key);
