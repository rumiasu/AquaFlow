-- Execute migration for address table (compatible with MySQL 5.7+)
-- Check and drop column if exists
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND COLUMN_NAME = 'tag');

SET @sql = IF(@col_exists > 0, 
  'ALTER TABLE `address` DROP COLUMN `tag`', 
  'SELECT ''tag column does not exist''');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Check and drop index if exists
SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND INDEX_NAME = 'idx_address_tag');

SET @sql2 = IF(@idx_exists > 0, 
  'DROP INDEX `idx_address_tag` ON `address`', 
  'SELECT ''idx_address_tag index does not exist''');

PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

DESCRIBE `address`;