-- STATUS: DANGEROUS - 包含 DELETE FROM inventory
-- 此文件包含危险的 DELETE FROM inventory 操作，禁止在新环境执行。
-- 其中 CREATE TABLE (stock_transfer, risk_alert) 和 INSERT 数据已被 schema.sql / seed_full_data.sql 吸收。

SET NAMES utf8mb4;

-- ============================================================
-- Phase 0: 水厂运营平台 - 数据库基础
-- ============================================================

-- 1. 给现有表加 station_id 列
-- ============================================================

-- 订单表加 station_id
ALTER TABLE orders ADD COLUMN station_id INT DEFAULT NULL COMMENT '所属水站ID' AFTER factory_id;
ALTER TABLE orders ADD INDEX idx_station_id (station_id);

-- 库存表加 station_id
ALTER TABLE inventory ADD COLUMN station_id INT DEFAULT NULL COMMENT '所属水站ID' AFTER id;
ALTER TABLE inventory ADD INDEX idx_inv_station_id (station_id);

-- 客户表加 station_id（已有该字段，确认索引）
ALTER TABLE customer ADD INDEX idx_cust_station_id (station_id);

-- 客户表加 role 字段（登录分离）
ALTER TABLE customer ADD COLUMN role TINYINT DEFAULT 1 COMMENT '角色: 1=站长 2=管理员' AFTER openid;

-- 2. 新建调拨表
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_transfer (
  id INT AUTO_INCREMENT PRIMARY KEY,
  from_station_id INT NOT NULL COMMENT '调出水站ID',
  to_station_id INT NOT NULL COMMENT '调入水站ID',
  water_type_id INT NOT NULL COMMENT '水类型ID',
  quantity INT NOT NULL COMMENT '调拨数量',
  status TINYINT DEFAULT 1 COMMENT '状态: 1=待审批 2=已审批 3=已完成 4=已取消',
  approve_note VARCHAR(200) DEFAULT NULL COMMENT '审批备注',
  complete_note VARCHAR(200) DEFAULT NULL COMMENT '完成备注',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_from_station (from_station_id),
  INDEX idx_to_station (to_station_id),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='水站间调拨记录';

-- 3. 新建风险预警表
-- ============================================================
CREATE TABLE IF NOT EXISTS risk_alert (
  id INT AUTO_INCREMENT PRIMARY KEY,
  station_id INT NOT NULL COMMENT '水站ID',
  alert_type VARCHAR(50) NOT NULL COMMENT '预警类型: ORDER_DECLINE/INVENTORY_BACKLOG/LOW_STOCK/CUSTOMER_LOSS/NO_ACTIVITY',
  alert_level TINYINT DEFAULT 1 COMMENT '预警等级: 1=提示 2=警告 3=紧急',
  title VARCHAR(100) NOT NULL COMMENT '预警标题',
  content TEXT COMMENT '预警详情',
  suggestion TEXT COMMENT '建议措施',
  status TINYINT DEFAULT 1 COMMENT '状态: 1=未读 2=已读 3=已处理',
  handle_note VARCHAR(200) DEFAULT NULL COMMENT '处理备注',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_station_id (station_id),
  INDEX idx_alert_type (alert_type),
  INDEX idx_alert_level (alert_level),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险预警记录';

-- 4. 种子数据 - 把现有数据分配到水站
-- ============================================================

-- 先确保有水站数据（如果 station 表为空则插入）
INSERT IGNORE INTO station (id, name, manager, phone, address, factory_id, status, create_time, update_time)
SELECT 1, '张店水站', '张站长', '13800000001', '淄博市张店区', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM station WHERE id = 1);

INSERT IGNORE INTO station (id, name, manager, phone, address, factory_id, status, create_time, update_time)
SELECT 2, '淄川水站', '淄站长', '13800000002', '淄博市淄川区', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM station WHERE id = 2);

INSERT IGNORE INTO station (id, name, manager, phone, address, factory_id, status, create_time, update_time)
SELECT 3, '博山水站', '博站长', '13800000003', '淄博市博山区', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM station WHERE id = 3);

-- 确保有水厂数据
INSERT IGNORE INTO factory (id, name, contact_person, contact_phone, address, status, create_time, update_time)
SELECT 1, '淄博总厂', '王总', '13900000001', '淄博市', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM factory WHERE id = 1);

-- 给客户分配水站（按 ID 范围分配）
UPDATE customer SET station_id = 1 WHERE id BETWEEN 1 AND 17 AND (station_id IS NULL OR station_id = 0);
UPDATE customer SET station_id = 2 WHERE id BETWEEN 18 AND 34 AND (station_id IS NULL OR station_id = 0);
UPDATE customer SET station_id = 3 WHERE id BETWEEN 35 AND 50 AND (station_id IS NULL OR station_id = 0);

-- 给订单分配水站（通过客户关联）
UPDATE orders o
  JOIN customer c ON o.customer_id = c.id
  SET o.station_id = c.station_id
  WHERE o.station_id IS NULL AND c.station_id IS NOT NULL;

-- 给库存分配水站（复制全局库存到各站，模拟多站库存）
-- 先清空旧库存（如果 station_id 全为 NULL）
DELETE FROM inventory WHERE station_id IS NOT NULL;

-- 把全局库存复制到3个站（每站一半库存）
INSERT INTO inventory (water_type_id, station_id, quantity, update_time)
SELECT water_type_id, 1, CEIL(quantity / 2), NOW()
FROM inventory WHERE station_id IS NULL;

UPDATE inventory SET quantity = FLOOR(quantity / 2) WHERE station_id IS NULL;

INSERT INTO inventory (water_type_id, station_id, quantity, update_time)
SELECT water_type_id, 2, FLOOR(quantity / 3), NOW()
FROM inventory WHERE station_id IS NULL;

INSERT INTO inventory (water_type_id, station_id, quantity, update_time)
SELECT water_type_id, 3, FLOOR(quantity / 3), NOW()
FROM inventory WHERE station_id IS NULL;

-- 删除全局库存（station_id 为 NULL 的）
DELETE FROM inventory WHERE station_id IS NULL;

-- 给管理员设置角色
UPDATE customer SET role = 2 WHERE name = '管理员' AND role = 1;
