-- STATUS: DUPLICATE of v11
-- orders.address_snapshot_lat/lng 已在 v11 中添加（精度10,6），此文件重复且精度不同（10,7），新环境无需执行。

-- ============================================================================
-- P0 整改补充：orders 地址快照经纬度列（存量阻断性缺失）
-- 迁移号：v12c
-- 背景：Orders 实体与 OrderMapper.save 均引用 address_snapshot_lat/lng，
--       但 orders 表从未建这两列，导致所有新订单插入 100% 报错（存量阻断 bug）。
-- 变更：补齐两张快照列。

ALTER TABLE orders
    ADD COLUMN address_snapshot_lat DECIMAL(10,7) NULL COMMENT '地址快照纬度' AFTER address_snapshot,
    ADD COLUMN address_snapshot_lng DECIMAL(10,7) NULL COMMENT '地址快照经度' AFTER address_snapshot_lat;