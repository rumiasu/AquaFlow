-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

SET NAMES utf8mb4;

-- 1. 创建 order_template 表
CREATE TABLE IF NOT EXISTS order_template (
  id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  water_type_id INT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  address_id INT DEFAULT NULL,
  special_note VARCHAR(500) DEFAULT NULL,
  enabled INT NOT NULL DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 创建 barrel_record 表
CREATE TABLE IF NOT EXISTS barrel_record (
  id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  quantity INT NOT NULL,
  status INT NOT NULL DEFAULT 1,
  deposit_refund DECIMAL(10,2) DEFAULT 0,
  note VARCHAR(500) DEFAULT NULL,
  handle_note VARCHAR(500) DEFAULT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  handle_time DATETIME DEFAULT NULL,
  INDEX idx_customer_id (customer_id),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. water_type 加 price 字段
ALTER TABLE water_type ADD COLUMN price DECIMAL(10,2) DEFAULT 0 AFTER note;

-- 4. address 加字段
ALTER TABLE address ADD COLUMN name VARCHAR(50) DEFAULT NULL AFTER customer_id;
ALTER TABLE address ADD COLUMN phone VARCHAR(20) DEFAULT NULL AFTER name;
ALTER TABLE address ADD COLUMN is_default TINYINT DEFAULT 0 AFTER phone;

-- 5. 给水类型灌价格
UPDATE water_type SET price = 20 WHERE id = 1;
UPDATE water_type SET price = 22 WHERE id = 2;
UPDATE water_type SET price = 18 WHERE id = 3;
UPDATE water_type SET price = 12 WHERE id = 4;
UPDATE water_type SET price = 14 WHERE id = 5;
UPDATE water_type SET price = 8 WHERE id = 6;
UPDATE water_type SET price = 10 WHERE id = 7;
UPDATE water_type SET price = 5 WHERE id = 8;
UPDATE water_type SET price = 6 WHERE id = 9;
UPDATE water_type SET price = 16 WHERE id = 10;

-- 6. 给现有地址补收件人信息
UPDATE address a LEFT JOIN customer c ON a.customer_id = c.id SET a.name = c.name, a.phone = c.phone WHERE a.name IS NULL;
