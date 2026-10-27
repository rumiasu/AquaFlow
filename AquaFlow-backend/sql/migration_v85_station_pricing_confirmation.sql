-- v85: one durable confirmation bit per station/product; run before paired backend.
-- No price, deposit, quantity or listing state is changed. Repeat-safe.
SET @af_pricing_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND COLUMN_NAME = 'pricing_confirmed');
SET @af_pricing_ddl = IF(@af_pricing_exists = 0,
 'ALTER TABLE inventory ADD COLUMN pricing_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT ''本站定价成功确认：0未知/未确认，1已确认''',
 'SELECT 1');
PREPARE af_pricing_stmt FROM @af_pricing_ddl;
EXECUTE af_pricing_stmt;
DEALLOCATE PREPARE af_pricing_stmt;

-- Only existing station-owned prices prove prior configuration. A selected row, stock,
-- listing switch, or the public product reference alone does not prove confirmation.
UPDATE inventory i JOIN product p ON p.id = i.product_id
SET i.pricing_confirmed = 1, i.update_time = i.update_time
WHERE i.pricing_confirmed = 0 AND
 (i.sale_price > 0 OR i.deposit_price > 0 OR
  (p.owner_station_id = i.station_id AND p.price > 0));
