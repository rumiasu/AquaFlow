-- P6: 客户选择水站 - 数据库迁移
-- STATUS: ONE_TIME_DATA_FIX
SET NAMES utf8mb4;

-- 1. 添加 current_station_id 字段
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'customer' AND column_name = 'current_station_id');
SET @sql = IF(@exists = 0, 'ALTER TABLE customer ADD COLUMN `current_station_id` bigint DEFAULT NULL COMMENT ''当前选择的服务水站（用户端切换，不影响归属）'' AFTER `openid`', 'SELECT ''column already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 为已有归属站的客户初始化 current_station_id = owner_station_id
UPDATE customer SET current_station_id = owner_station_id WHERE owner_station_id IS NOT NULL AND current_station_id IS NULL;

-- 3. 为没有归属站但有 station_id 的客户初始化 current_station_id = station_id
UPDATE customer SET current_station_id = station_id WHERE station_id IS NOT NULL AND current_station_id IS NULL AND owner_station_id IS NULL;
