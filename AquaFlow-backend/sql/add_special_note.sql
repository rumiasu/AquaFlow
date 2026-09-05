-- STATUS: SUPERSEDED BY schema.sql
-- orders.special_note 列已在 schema.sql 中定义，新环境无需执行。

-- AquaFlow 订单表增加备注字段
ALTER TABLE orders ADD COLUMN `special_note` VARCHAR(500) DEFAULT NULL COMMENT '特殊说明' AFTER `source`;
