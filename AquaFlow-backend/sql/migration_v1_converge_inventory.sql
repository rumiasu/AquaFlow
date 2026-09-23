-- ============================================================
-- V1 收敛: inventory 表 water_type_id → product_id 生产级迁移
-- ============================================================
-- 执行前:
--   1. DESCRIBE inventory;  -- 确认当前列
--   2. SHOW INDEX FROM inventory;  -- 确认当前索引名
--   3. SELECT COUNT(*) FROM inventory;  -- 确认数据量
--
-- 执行方式: 逐段执行，每段确认无报错后再执行下一段
-- ============================================================

-- ============================================================
-- Phase 0: 确认当前状态（只读，不会修改任何数据）
-- ============================================================
DESCRIBE `inventory`;
SHOW INDEX FROM `inventory`;

-- ============================================================
-- Phase 1: 新增 product_id 列（ nullable，不影响现有数据）
-- ============================================================
-- 如果 product_id 列已存在，此语句会报 "Duplicate column"，可安全跳过
ALTER TABLE `inventory`
  ADD COLUMN `product_id` bigint NULL COMMENT '商品ID（由 water_type_id 迁移而来）' AFTER `station_id`;

-- 确认新列已添加
SELECT `id`, `water_type_id`, `product_id`, `station_id`, `quantity`
FROM `inventory` LIMIT 5;

-- ============================================================
-- Phase 2: 数据迁移（water_type_id → product_id）
-- ============================================================
-- 水类型 ID 与商品 ID 一一对应（由 migrate_missing_tables.sql 保证）
UPDATE `inventory` SET `product_id` = `water_type_id` WHERE `product_id` IS NULL;

-- 验证：应返回 0 行
SELECT * FROM `inventory` WHERE `product_id` IS NULL;

-- 验证：抽样检查数据一致性
SELECT `id`, `water_type_id`, `product_id`, `station_id`, `quantity`
FROM `inventory`
WHERE `water_type_id` != `product_id`
LIMIT 10;

-- ============================================================
-- Phase 3: 将 product_id 改为 NOT NULL
-- ============================================================
ALTER TABLE `inventory`
  MODIFY COLUMN `product_id` bigint NOT NULL COMMENT '商品ID';

-- ============================================================
-- Phase 4: 删除旧索引（先确认索引名）
-- ============================================================
-- 如果 Phase 0 中 SHOW INDEX 确认索引名不同，请替换下面的名称
-- 常见命名: uk_inventory_station_water / idx_inventory_station_water
-- 如果索引不存在，DROP INDEX 会报错，可安全跳过

-- 删除唯一索引
ALTER TABLE `inventory` DROP INDEX `uk_inventory_station_water`;

-- 删除普通索引
ALTER TABLE `inventory` DROP INDEX `idx_inventory_station_water`;

-- ============================================================
-- Phase 5: 删除旧列
-- ============================================================
ALTER TABLE `inventory` DROP COLUMN `water_type_id`;

-- ============================================================
-- Phase 6: 创建新索引 + 外键
-- ============================================================
-- 唯一索引: 同一水站同一商品只能有一条库存
ALTER TABLE `inventory`
  ADD UNIQUE KEY `uk_inventory_station_product` (`station_id`, `product_id`);

-- 普通索引: 支持按商品查库存
ALTER TABLE `inventory`
  ADD KEY `idx_inventory_product` (`product_id`);

-- 外键: 防止幽灵库存（商品删除时库存必须先处理）
ALTER TABLE `inventory`
  ADD CONSTRAINT `fk_inventory_product`
  FOREIGN KEY (`product_id`) REFERENCES `product` (`id`);

-- ============================================================
-- Phase 7: 最终验证
-- ============================================================
DESCRIBE `inventory`;
SHOW INDEX FROM `inventory`;

-- 确认无孤儿数据
SELECT i.`id`, i.`station_id`, i.`product_id`, i.`quantity`
FROM `inventory` i
LEFT JOIN `product` p ON i.`product_id` = p.`id`
WHERE p.`id` IS NULL;
-- 期望: 0 行
