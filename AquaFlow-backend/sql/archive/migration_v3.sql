-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- V3: 支付系统重构
-- 1. 订单加 payment_method 字段
-- 2. 创建 payment_record 支付记录表
-- 3. ticket_record 加 ticket_source 字段
-- 4. 创建 station_config 站点配置表

-- 订单加支付方式
ALTER TABLE orders ADD COLUMN payment_method TINYINT DEFAULT NULL COMMENT '支付方式: 1=微信 2=现金 3=水票 4=挂账' AFTER payment_status;

-- 支付记录表
CREATE TABLE IF NOT EXISTS payment_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL COMMENT '订单ID',
  customer_id BIGINT NOT NULL COMMENT '客户ID',
  amount DECIMAL(10,2) NOT NULL COMMENT '支付金额',
  payment_method TINYINT NOT NULL COMMENT '支付方式: 1=微信 2=现金 3=水票 4=挂账',
  ticket_water_type_id BIGINT DEFAULT NULL COMMENT '水票支付时关联的水类型ID',
  ticket_qty INT DEFAULT NULL COMMENT '水票支付张数',
  status TINYINT DEFAULT 1 COMMENT '状态: 1=待支付 2=已支付 3=已退款 4=已取消',
  note VARCHAR(200) DEFAULT NULL COMMENT '备注',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_order_id (order_id),
  INDEX idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付记录表';

-- 水票记录加来源
ALTER TABLE ticket_record ADD COLUMN ticket_source TINYINT DEFAULT 1 COMMENT '来源: 1=线上 2=线下' AFTER source;

-- 站点支付配置表
CREATE TABLE IF NOT EXISTS station_payment_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  station_id BIGINT DEFAULT NULL COMMENT '水站ID, NULL=全局默认',
  enable_wechat TINYINT DEFAULT 1 COMMENT '启用微信支付',
  enable_cash TINYINT DEFAULT 1 COMMENT '启用现金',
  enable_cod TINYINT DEFAULT 1 COMMENT '启用货到付款',
  enable_ticket_online TINYINT DEFAULT 1 COMMENT '启用线上购买水票',
  enable_ticket_offline TINYINT DEFAULT 1 COMMENT '启用线下水票录入',
  enable_credit TINYINT DEFAULT 0 COMMENT '启用挂账(月结)',
  enable_mixed TINYINT DEFAULT 0 COMMENT '启用混合支付',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点支付配置表';

-- 插入默认全局配置
INSERT IGNORE INTO station_payment_config (id, station_id, enable_wechat, enable_cash, enable_cod, enable_credit, enable_mixed)
VALUES (1, NULL, 1, 1, 1, 0, 0);
