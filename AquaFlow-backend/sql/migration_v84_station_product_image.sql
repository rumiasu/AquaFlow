-- v84: station-scoped product image override. Add before updating the backend; no data rewrite.
SET @af_image_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory' AND COLUMN_NAME = 'image_object_name_override');
SET @af_image_ddl = IF(@af_image_exists = 0,
 'ALTER TABLE inventory ADD COLUMN image_object_name_override VARCHAR(512) DEFAULT NULL COMMENT ''本站商品图覆盖，NULL使用商品原图''',
 'SELECT 1');
PREPARE af_image_stmt FROM @af_image_ddl;
EXECUTE af_image_stmt;
DEALLOCATE PREPARE af_image_stmt;
