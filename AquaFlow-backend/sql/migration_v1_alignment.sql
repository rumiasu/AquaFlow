-- ============================================================
-- AquaFlow v1 对齐增量迁移脚本
-- 生成时间: 2026-08-24
-- 用途: 对已经执行过 migration_v1_final.sql 的生产/测试库，
--       单独执行本次 V1 实体修改对齐所需的增量 DDL
-- 注意: 执行前请备份数据库！可重复执行（部分语句用 IF EXISTS/条件 UPDATE）。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- A1. customer 表：清理多余列 tags
--     (total_consumption / avg_cycle_days / role 已在 migration_v1_final.sql 中删除)
-- ============================================================

ALTER TABLE `customer`
  DROP COLUMN IF EXISTS `tags`;

-- ============================================================
-- A2. staff 表：增加绑定申请列 + 绑定状态列 + 联合索引
-- ============================================================

-- 2.1 增加 apply_station_id
ALTER TABLE `staff`
  ADD COLUMN IF NOT EXISTS `apply_station_id` bigint DEFAULT NULL COMMENT '申请绑定的水站ID' AFTER `station_id`;

-- 2.2 增加 binding_status
ALTER TABLE `staff`
  ADD COLUMN IF NOT EXISTS `binding_status` varchar(20) NOT NULL DEFAULT 'UNBOUND' COMMENT '绑定状态 UNBOUND/PENDING/BOUND/REJECTED/PENDING_UNBIND' AFTER `apply_station_id`;

-- 2.3 增加索引
ALTER TABLE `staff`
  ADD KEY IF NOT EXISTS `idx_apply_station_status` (`apply_station_id`, `binding_status`);

-- ============================================================
-- A3. 初始化 staff.binding_status 历史数据
--     保证所有存量员工 binding_status 非空，默认 UNBOUND
-- ============================================================

UPDATE `staff`
SET `binding_status` = 'UNBOUND'
WHERE `binding_status` IS NULL OR `binding_status` = '';

-- ============================================================
-- A4. orders 表注释调整
--     A4.1 delivery_station_id 列注释
--     A4.2 status 列注释 (改为 5 态说明)
-- ============================================================

ALTER TABLE `orders`
  MODIFY COLUMN `delivery_station_id` bigint DEFAULT NULL COMMENT '实际履约配送水站ID，可与 owner_station_id 不同',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '订单状态：1 待配送 3 配送中 4 已送达 5 已完成 6 已取消（是否分配用 delivery_staff_id 判断）';

-- ============================================================
-- A5. 历史订单 status 值修正 (V1 8 态 -> 5 态合并)
--
-- 旧态 -> 新态 映射：
--   2 ASSIGNED(待分配)   -> 1 PENDING(待配送)   是否分配用 delivery_staff_id IS NULL 判定
--   7 REJECTED(已取消)   -> 6 CANCELLED(已取消)  与"取消"语义一致，并入 6
--   8 BATCHED(已组批)    -> 1 PENDING(待配送)   组批态取消，统一回待配送
--
-- 保留不变：
--   1 待处理 -> 1 待配送 (同值)
--   3 已分配 -> 保留 3 (同"配送中/待配送员接单"，若 delivery_staff_id 有值即为已分配)
--   4 配送中 -> 4 配送中 (同值)
--   5 已送达 -> 5 已送达 (同值)
--   6 已完成 -> 5 已完成 (如 V1 实体定义里 5=已完成)
-- ============================================================

-- 2 -> 1
UPDATE `orders` SET `status` = 1 WHERE `status` = 2;

-- 7 -> 6
UPDATE `orders` SET `status` = 6 WHERE `status` = 7;

-- 8 -> 1
UPDATE `orders` SET `status` = 1 WHERE `status` = 8;

-- ============================================================
-- 完成
-- ============================================================

SET FOREIGN_KEY_CHECKS = 1;

SELECT 'V1 alignment migration (staff binding cols, order status 5-state, customer tags cleanup) applied successfully!' AS message;
