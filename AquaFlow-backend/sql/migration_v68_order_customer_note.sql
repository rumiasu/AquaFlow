-- v68: separate customer-provided order notes from internal workflow history.
-- No backfill: historical special_note may mix customer text and staff events,
-- and there is no safe way to infer boundaries without changing customer data.
SET @db := DATABASE();
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'orders' AND column_name = 'customer_note'
);
SET @sql := IF(@has_col = 0,
  'ALTER TABLE orders ADD COLUMN customer_note varchar(500) DEFAULT NULL COMMENT ''Customer note snapshot, separate from internal special_note'' AFTER special_note',
  'SELECT ''skip: orders.customer_note already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
