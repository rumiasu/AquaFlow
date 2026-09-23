-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- inventory 表增加复合索引，优化按水站查询库存的性能
ALTER TABLE inventory ADD INDEX idx_inventory_station_water (station_id, water_type_id);
