-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- 用户端功能修复迁移 v11
-- 1. orders 增加地址快照经纬度（配送导航用，避免地址被改后导航失效）
ALTER TABLE orders ADD COLUMN address_snapshot_lat DECIMAL(10,6) DEFAULT NULL COMMENT '下单地址快照纬度';
ALTER TABLE orders ADD COLUMN address_snapshot_lng DECIMAL(10,6) DEFAULT NULL COMMENT '下单地址快照经度';

-- 2. barrel_record 增加水类型归属（按水型口径核算持有桶数）
ALTER TABLE barrel_record ADD COLUMN water_type_id INT DEFAULT NULL COMMENT '退桶归属水类型ID，NULL 表示历史数据/未指定';

-- 3. 历史退桶记录按订单水型回填归属（仅能回填有对应订单记录的数据）
UPDATE barrel_record br
LEFT JOIN (
    SELECT customer_id, MIN(water_type_id) AS water_type_id
    FROM orders
    WHERE water_type_id IS NOT NULL
    GROUP BY customer_id
) o ON o.customer_id = br.customer_id
SET br.water_type_id = o.water_type_id
WHERE br.water_type_id IS NULL;