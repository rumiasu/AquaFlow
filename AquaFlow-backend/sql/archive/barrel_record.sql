-- STATUS: DUPLICATE of migration_full.sql
-- barrel_record 表已在 migration_full.sql 中创建，此文件重复，新环境无需执行。

-- 水桶退桶记录表
CREATE TABLE IF NOT EXISTS `barrel_record` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
  `customer_id` INT NOT NULL COMMENT '客户ID',
  `quantity` INT NOT NULL COMMENT '退桶数量',
  `status` INT NOT NULL DEFAULT 1 COMMENT '状态：1=待处理 2=已确认 3=已退还押金 4=已驳回',
  `deposit_refund` DECIMAL(10,2) DEFAULT 0 COMMENT '退押金金额',
  `note` VARCHAR(500) COMMENT '客户备注',
  `handle_note` VARCHAR(500) COMMENT '处理备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `handle_time` DATETIME COMMENT '处理时间',
  INDEX `idx_customer_id` (`customer_id`),
  INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退桶记录表';
