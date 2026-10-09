-- v79: 人工工资录入意图。仅结构升级；不改存量金额、不自动打款。
-- 执行前备份，停止旧录入请求；与新工资页/服务一起发布。存量键保持NULL。
SET @db = DATABASE();
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_earning' AND COLUMN_NAME='idempotency_key')=0,
  'ALTER TABLE staff_earning ADD COLUMN idempotency_key varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''人工录入意图键''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_earning' AND COLUMN_NAME='request_digest')=0,
  'ALTER TABLE staff_earning ADD COLUMN request_digest char(64) DEFAULT NULL COMMENT ''人工录入内容摘要''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_earning' AND INDEX_NAME='uk_earning_intent')=0,
  'ALTER TABLE staff_earning ADD UNIQUE KEY uk_earning_intent (station_id,idempotency_key)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
