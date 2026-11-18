-- 本站商品展示资料与零售促销。先加列再部署服务端，无需回填。
SET @af_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory' AND COLUMN_NAME='promotion_price');
SET @af_ddl = IF(@af_exists=0,
 'ALTER TABLE inventory ADD COLUMN promotion_price DECIMAL(10,2) DEFAULT NULL COMMENT ''本站零售促销价，NULL表示未促销''', 'SELECT 1');
PREPARE af_station_product FROM @af_ddl;
EXECUTE af_station_product;
DEALLOCATE PREPARE af_station_product;

SET @af_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory' AND COLUMN_NAME='station_product_name');
SET @af_ddl = IF(@af_exists=0,
 'ALTER TABLE inventory ADD COLUMN station_product_name VARCHAR(100) DEFAULT NULL COMMENT ''本站商品名称覆盖，NULL使用商品库名称''', 'SELECT 1');
PREPARE af_station_product FROM @af_ddl;
EXECUTE af_station_product;
DEALLOCATE PREPARE af_station_product;

SET @af_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inventory' AND COLUMN_NAME='station_product_spec');
SET @af_ddl = IF(@af_exists=0,
 'ALTER TABLE inventory ADD COLUMN station_product_spec VARCHAR(100) DEFAULT NULL COMMENT ''本站商品规格覆盖，NULL使用商品库规格''', 'SELECT 1');
PREPARE af_station_product FROM @af_ddl;
EXECUTE af_station_product;
DEALLOCATE PREPARE af_station_product;
