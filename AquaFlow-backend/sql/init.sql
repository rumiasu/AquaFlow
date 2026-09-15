-- ============================================================
-- AquaFlow 数据库一键初始化 (init.sql)
-- 用途: 全新空数据库初始化 —— 只建结构，不含种子数据
-- 用法: 在 AquaFlow-backend/sql/ 目录下执行
--       mysql -u root -p < init.sql
-- 说明:
--   1. schema.sql 是从当前运行库导出的完整 DDL（37 张业务表，无视图），
--      水厂/厂长相关对象已于 2026-09-11 全部移除，不要再往里补。
--   2. 原 seed_full_data.sql 等种子脚本停留在 V1 大迁移之前
--      （引用 water_type / staff.password / factory 等已删对象），
--      跑不起来且修复成本≈重写，已于 2026-09-11 移入 sql/archive/ 仅作历史参考。
--   3. 需要基础数据请手动创建：先建水站（station）→ 建员工（staff）→
--      配库存（inventory）/水票开关 → 再注册顾客下单。
--      开发账号可用 sql/seed_dev_account.sql（如需）。
-- ============================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE aquaflow;

-- 2. 执行 schema.sql (结构)
SOURCE schema.sql;

-- 3. 种子数据：已不再随 init.sql 自动执行
--    （历史脚本见 archive/，均不可直接用）
