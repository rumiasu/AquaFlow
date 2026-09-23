-- 重置所有员工密码为 BCrypt 编码的 123456
-- 密码: 123456 -> BCrypt hash (由应用启动时 PasswordInitializer 自动初始化)
-- 这里先清空 password_hash 让 PasswordInitializer 重新生成

UPDATE staff SET password_hash = NULL WHERE id NOT IN (16);
