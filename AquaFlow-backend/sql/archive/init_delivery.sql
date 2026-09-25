-- STATUS: TEST_DATA
-- 测试数据脚本，禁止在新环境执行。

-- 配送员小程序初始化数据
-- 添加测试配送员

-- 1. 添加配送员（如果不存在）
INSERT INTO staff (name, phone, password, factory_id, station_id, role, status, create_time, update_time)
SELECT '配送员', '13800000000', NULL, NULL, 1, 'DELIVERY', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM staff WHERE role = 'DELIVERY');

-- 密码由 PasswordInitializer 启动时自动初始化

-- 2. 添加一些测试订单（status=4 已组批待出发，供配送员接单）
-- 假设客户ID=1, 水类型ID=1, 地址ID=1 存在
INSERT INTO orders (customer_id, station_id, address_id, water_type_id, quantity, source, status, payment_method, create_time, update_time)
SELECT 1, 1, 1, 1, 2, 3, 4, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE status = 4 LIMIT 1);

-- 3. 添加一条配送中的订单（用于测试完成配送）
INSERT INTO orders (customer_id, station_id, address_id, water_type_id, quantity, source, status, payment_method, delivery_staff_id, create_time, update_time)
SELECT 1, 1, 1, 1, 1, 3, 2, 1, (SELECT id FROM staff WHERE role = 'DELIVERY' LIMIT 1), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE status = 2 LIMIT 1);
