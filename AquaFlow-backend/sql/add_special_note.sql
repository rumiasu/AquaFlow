-- AquaFlow 订单表增加备注字段
ALTER TABLE orders ADD COLUMN `special_note` VARCHAR(500) DEFAULT NULL COMMENT '特殊说明' AFTER `source`;
