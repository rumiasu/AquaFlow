-- ============================================================
-- AquaFlow 数据库一键初始化 (init.sql)
-- 用途: 全新空数据库初始化
-- 用法: mysql -u root -p < init.sql
-- ============================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE aquaflow;

-- 2. 执行 schema.sql (结构)
SOURCE schema.sql;

-- 3. 执行 seed_full_data.sql (种子数据)
SOURCE seed_full_data.sql;
