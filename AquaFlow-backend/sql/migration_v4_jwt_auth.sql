-- ============================================================
-- V4: JWT 认证体系 - 数据库迁移
-- 1. staff 表加 password 列
-- 2. 创建 user_token 表（存储 refresh_token）
-- 3. 插入 admin 超级管理员记录（如果没有）
-- ============================================================

-- 1. staff 表加 password 列
ALTER TABLE staff ADD COLUMN password VARCHAR(255) DEFAULT NULL COMMENT 'BCrypt加密后的密码' AFTER phone;

-- 2. 创建 user_token 表
CREATE TABLE IF NOT EXISTS user_token (
  id INT AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL COMMENT '用户ID（staff.id 或 customer.id）',
  user_type VARCHAR(20) NOT NULL COMMENT '用户类型: staff / customer',
  refresh_token VARCHAR(500) NOT NULL COMMENT 'refresh_token字符串',
  expire_time DATETIME NOT NULL COMMENT '过期时间',
  device_info VARCHAR(100) DEFAULT NULL COMMENT '设备标识（可选）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user (user_id, user_type),
  INDEX idx_refresh_token (refresh_token),
  INDEX idx_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户Token表，存储refresh_token用于无感续期';

-- 3. 插入 admin 超级管理员记录（如果不存在）
-- 初始密码由 PasswordInitializer 组件在首次启动时自动设置为 BCrypt(admin123)
INSERT IGNORE INTO staff(name, phone, password, factory_id, station_id, role, status, create_time, update_time)
VALUES('admin', '00000000000', NULL, 1, NULL, 'FACTORY_ADMIN', 1, NOW(), NOW());

-- ============================================================
-- 说明：
-- 1. 执行此 SQL 后启动应用，PasswordInitializer 会自动：
--    - 为 admin 设置 BCrypt 加密的 admin123 密码
--    - 为所有 password 为 NULL 的员工设置 BCrypt 加密的 123456 密码
-- 2. 登录后请通过 /api/auth/change-password 修改密码
-- 3. 迁移过渡期：password 为 NULL 的员工仍可用 123456 登录
-- ============================================================
