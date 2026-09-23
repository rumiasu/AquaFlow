-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- 订单表增加空桶差异记录字段
ALTER TABLE orders ADD COLUMN barrel_discrepancy INT DEFAULT 0 COMMENT '空桶差异：正数表示少还，负数表示多还';
ALTER TABLE orders ADD COLUMN barrel_discrepancy_note VARCHAR(200) DEFAULT NULL COMMENT '空桶差异说明';
