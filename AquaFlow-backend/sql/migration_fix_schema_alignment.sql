-- ============================================================
-- 迁移脚本：修复 barrel_record / ticket_account / address schema 与 Mapper 对齐
-- 执行前请备份数据库
-- ============================================================

-- 1. barrel_record: 添加 Mapper 期望但 schema 缺失的列
ALTER TABLE `barrel_record`
  ADD COLUMN IF NOT EXISTS `station_id` bigint DEFAULT NULL COMMENT '所属水站ID' AFTER `customer_id`,
  ADD COLUMN IF NOT EXISTS `product_id` bigint DEFAULT NULL COMMENT '商品ID(桶装水)' AFTER `station_id`,
  ADD COLUMN IF NOT EXISTS `type` tinyint DEFAULT NULL COMMENT '1新增押金桶 2退桶 3丢失 4损坏 5赔偿 6人工调整' AFTER `product_id`,
  ADD COLUMN IF NOT EXISTS `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID' AFTER `quantity`,
  ADD COLUMN IF NOT EXISTS `operator_id` bigint DEFAULT NULL COMMENT '操作员ID' AFTER `note`;

-- 添加索引（如果不存在）
ALTER TABLE `barrel_record`
  ADD INDEX IF NOT EXISTS `idx_barrel_record_station` (`station_id`);

-- 2. ticket_account: water_type_id → product_id + 添加 update_time
-- 先检查是否已有 product_id 列，如果没有则添加
SET @has_product_id = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ticket_account' AND COLUMN_NAME = 'product_id');

-- 如果没有 product_id 列，则添加
SET @sql = IF(@has_product_id = 0,
  'ALTER TABLE `ticket_account` ADD COLUMN `product_id` bigint DEFAULT NULL COMMENT ''商品ID(桶装水)'' AFTER `customer_id`',
  'SELECT ''product_id column already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 update_time 列
SET @has_update_time = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ticket_account' AND COLUMN_NAME = 'update_time');

SET @sql2 = IF(@has_update_time = 0,
  'ALTER TABLE `ticket_account` ADD COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
  'SELECT ''update_time column already exists''');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 注意：如果已有数据使用了 water_type_id，需要手动迁移数据：
-- UPDATE ticket_account SET product_id = water_type_id WHERE product_id IS NULL AND water_type_id IS NOT NULL;
-- 确认数据迁移完成后，可以考虑删除 water_type_id 列（可选，不急）

-- 3. address: 删除废弃 tag 列
SET @has_tag = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND COLUMN_NAME = 'tag');

SET @sql3 = IF(@has_tag > 0,
  'ALTER TABLE `address` DROP COLUMN `tag`',
  'SELECT ''tag column already removed''');
PREPARE stmt3 FROM @sql3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

-- 删除 tag 索引（如果存在）
SET @has_idx_tag = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND INDEX_NAME = 'idx_address_tag');

SET @sql4 = IF(@has_idx_tag > 0,
  'ALTER TABLE `address` DROP INDEX `idx_address_tag`',
  'SELECT ''idx_address_tag already removed''');
PREPARE stmt4 FROM @sql4;
EXECUTE stmt4;
DEALLOCATE PREPARE stmt4;

-- 4. station: 添加 creator_staff_id 列（站长创建站点归属）
SET @has_creator = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station' AND COLUMN_NAME = 'creator_staff_id');

SET @sql5 = IF(@has_creator = 0,
  'ALTER TABLE `station` ADD COLUMN `creator_staff_id` bigint DEFAULT NULL COMMENT ''创建者站长ID'' AFTER `status`',
  'SELECT ''creator_staff_id column already exists''');
PREPARE stmt5 FROM @sql5;
EXECUTE stmt5;
DEALLOCATE PREPARE stmt5;

-- 迁移现有数据：将 staff 表中已绑定 station_id 的记录回填到 station.creator_staff_id
-- 仅当 station.creator_staff_id 为空且 staff.station_id 匹配时更新
SET @sql6 = 'UPDATE `station` s 
  JOIN `staff` st ON st.station_id = s.id 
  SET s.creator_staff_id = st.id 
  WHERE s.creator_staff_id IS NULL 
    AND st.station_id IS NOT NULL 
    AND st.role = ''STATION_MANAGER''';
PREPARE stmt6 FROM @sql6;
EXECUTE stmt6;
DEALLOCATE PREPARE stmt6;

-- 5. order_template: 确保 station_id 列存在（schema.sql 已有，但数据库可能未迁移）
SET @has_template_station = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_template' AND COLUMN_NAME = 'station_id');

SET @sql7 = IF(@has_template_station = 0,
  'ALTER TABLE `order_template` ADD COLUMN `station_id` bigint DEFAULT NULL COMMENT ''所属水站ID'' AFTER `customer_id`',
  'SELECT ''order_template.station_id column already exists''');
PREPARE stmt7 FROM @sql7;
EXECUTE stmt7;
DEALLOCATE PREPARE stmt7;

-- 6. order_template_item: 确保有索引
SET @has_template_item_idx = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_template_item' AND INDEX_NAME = 'idx_template_id');

SET @sql8 = IF(@has_template_item_idx = 0,
  'ALTER TABLE `order_template_item` ADD INDEX `idx_template_id` (`template_id`)',
  'SELECT ''idx_template_id already exists''');
PREPARE stmt8 FROM @sql8;
EXECUTE stmt8;
DEALLOCATE PREPARE stmt8;
