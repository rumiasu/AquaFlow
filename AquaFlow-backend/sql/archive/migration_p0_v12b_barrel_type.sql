-- STATUS: DUPLICATE of v11
-- barrel_record.water_type_id 已在 v11 中添加，此文件重复，新环境无需执行。

-- ============================================================================
-- P0 整改补充：桶资产按水类型归属
-- 迁移号：v12b
-- 背景：BarrelMapper.insert / getBarrelSummaryByType 一直引用 water_type_id，
--       但 barrel_record 表从未创建该列，导致退桶查询长期报错（存量隐患）。
-- 变更：
--  1. barrel_record 增加 water_type_id INT NULL（退桶资产按水类型归属，用于按水类型
--     独立计算「当前可持有桶」）。
--  2. 旧数据（17 条）无法可靠反推水类型，置 NULL：
--     归属计算不扣减这些行（与历史行为一致，避免误扣）。
-- 说明：新增退桶记录必须携带 water_type_id（BarrelServiceImpl 提供方补传），
--       否则不可归属。

ALTER TABLE barrel_record
    ADD COLUMN water_type_id INT NULL COMMENT '水类型ID（退桶资产按水类型归属）';

-- 后续可加索引
-- ALTER TABLE barrel_record ADD INDEX idx_br_customer_type (customer_id, water_type_id);