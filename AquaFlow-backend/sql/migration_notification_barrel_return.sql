-- ============================================================
-- 迁移脚本：客户通知表 + 退桶审批字段补全
-- 涉及变更：
--   1. 新建 customer_notification 表（拒单/临时外派提醒）
--   2. barrel_record 补齐退桶审批字段（status/handle_note/deposit_refund）
-- 执行前请备份数据库
-- ============================================================

-- 1. 客户通知表
CREATE TABLE IF NOT EXISTS `customer_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `type` varchar(32) NOT NULL COMMENT '通知类型: REJECTED=拒单取消, TEMP_DISPATCH=临时外派配送',
  `title` varchar(100) NOT NULL COMMENT '通知标题',
  `content` varchar(500) NOT NULL COMMENT '通知内容',
  `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `is_read` tinyint NOT NULL DEFAULT 0 COMMENT '是否已读: 0=未读, 1=已读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_cn_customer_read` (`customer_id`, `is_read`),
  KEY `idx_cn_order` (`related_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户通知表';

-- 2. barrel_record 退桶审批字段（如缺失则补齐，migration_full.sql 已含，此处兜底）
SET @has_status = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record' AND COLUMN_NAME = 'status');
SET @sql1 = IF(@has_status = 0,
  'ALTER TABLE `barrel_record` ADD COLUMN `status` int NOT NULL DEFAULT 1 COMMENT ''退桶申请状态:1待处理 2已确认收到空桶 3已退押金 4已驳回''',
  'SELECT ''barrel_record.status already exists''');
PREPARE stmt1 FROM @sql1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @has_handle_note = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record' AND COLUMN_NAME = 'handle_note');
SET @sql2 = IF(@has_handle_note = 0,
  'ALTER TABLE `barrel_record` ADD COLUMN `handle_note` varchar(500) DEFAULT NULL COMMENT ''处理备注（站长驳回原因等）''',
  'SELECT ''barrel_record.handle_note already exists''');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

SET @has_deposit_refund = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record' AND COLUMN_NAME = 'deposit_refund');
SET @sql3 = IF(@has_deposit_refund = 0,
  'ALTER TABLE `barrel_record` ADD COLUMN `deposit_refund` decimal(10,2) DEFAULT 0 COMMENT ''退押金金额（仅type=2退桶有意义）''',
  'SELECT ''barrel_record.deposit_refund already exists''');
PREPARE stmt3 FROM @sql3; EXECUTE stmt3; DEALLOCATE PREPARE stmt3;

SET @has_handle_time = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record' AND COLUMN_NAME = 'handle_time');
SET @sql4 = IF(@has_handle_time = 0,
  'ALTER TABLE `barrel_record` ADD COLUMN `handle_time` datetime DEFAULT NULL COMMENT ''处理时间''',
  'SELECT ''barrel_record.handle_time already exists''');
PREPARE stmt4 FROM @sql4; EXECUTE stmt4; DEALLOCATE PREPARE stmt4;
