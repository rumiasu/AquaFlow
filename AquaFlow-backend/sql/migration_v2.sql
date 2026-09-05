-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

SET NAMES utf8mb4;

-- 1. order_template 加 name 和 is_default 字段
ALTER TABLE order_template ADD COLUMN name VARCHAR(100) DEFAULT NULL COMMENT '模板名称(如:家里/公司)' AFTER customer_id;
ALTER TABLE order_template ADD COLUMN is_default TINYINT DEFAULT 0 COMMENT '是否默认模板 0=否 1=是' AFTER enabled;

-- 2. 创建 order_template_item 表（模板明细，支持多商品）
CREATE TABLE IF NOT EXISTS order_template_item (
  id INT AUTO_INCREMENT PRIMARY KEY,
  template_id INT NOT NULL COMMENT '模板ID',
  water_type_id INT NOT NULL COMMENT '水类型ID',
  quantity INT NOT NULL DEFAULT 1 COMMENT '桶数',
  INDEX idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. orders 加快照字段
ALTER TABLE orders ADD COLUMN receiver_name VARCHAR(50) DEFAULT NULL COMMENT '收件人姓名快照' AFTER special_note;
ALTER TABLE orders ADD COLUMN receiver_phone VARCHAR(20) DEFAULT NULL COMMENT '收件人电话快照' AFTER receiver_name;
ALTER TABLE orders ADD COLUMN address_snapshot VARCHAR(500) DEFAULT NULL COMMENT '地址快照' AFTER receiver_phone;

-- 4. address 加 label 字段
ALTER TABLE address ADD COLUMN label VARCHAR(50) DEFAULT NULL COMMENT '标签(家/公司/父母等)' AFTER is_default;
