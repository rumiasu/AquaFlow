-- STATUS: DUPLICATE of migration_full.sql
-- order_template 表已在 migration_full.sql 中创建，此文件重复，新环境无需执行。

-- 默认订单模板表
CREATE TABLE IF NOT EXISTS `order_template` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '模板ID',
  `customer_id` INT NOT NULL COMMENT '客户ID',
  `water_type_id` INT NOT NULL COMMENT '水类型ID',
  `quantity` INT NOT NULL DEFAULT 1 COMMENT '数量（桶数）',
  `address_id` INT COMMENT '配送地址ID',
  `special_note` VARCHAR(500) COMMENT '备注',
  `enabled` INT NOT NULL DEFAULT 1 COMMENT '是否启用：0=关闭 1=开启',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  INDEX `idx_customer_id` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='默认订单模板表';
