-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- ============================================================================
-- P0 整改：安全与服务端规则
-- 迁移号：v12
-- 变更：
--  1. water_type 增加「单次正常购买上限」字段 max_per_order
--     语义：单次购买数量独立上限，与客户当前持有桶数量无关（默认 4 桶）。
--     规则：允许购买数量 = min(max_per_order, 当前可持有桶 + 本次新增押金桶)
--          超出可持有部分必须支付新增桶押金，由服务端计算，不信任前端数值。
-- 兼容：仅新增列（可空/带默认值），不影响现有数据与查询。
-- ============================================================================

ALTER TABLE water_type
    ADD COLUMN max_per_order INT NOT NULL DEFAULT 4 COMMENT '单次正常购买上限(桶)' AFTER deposit;

-- 旧数据回填：已有水类型统一按默认上限 4（可在站内商品管理调整）
UPDATE water_type SET max_per_order = 4 WHERE max_per_order IS NULL OR max_per_order <= 0;