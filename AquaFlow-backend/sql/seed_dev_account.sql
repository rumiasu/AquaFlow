-- ============================================================
-- 为开发模式测试账号注入丰富的业务数据
-- ============================================================
SET NAMES utf8mb4;

SET @dev_id = (SELECT id FROM customer WHERE openid = 'dev-openid-001' LIMIT 1);

-- 若不存在则创建
INSERT INTO customer (name, phone, openid, customer_type, station_id, deposit_balance, total_orders, total_consumption, avg_cycle_days, tags, first_order_time, last_delivery_time)
SELECT '测试用户', '13800138000', 'dev-openid-001', 1, 1, 300.00, 56, 1120.00, 7, 'VIP,测试', '2024-06-01', '2026-07-21'
WHERE @dev_id IS NULL;

SET @dev_id = (SELECT id FROM customer WHERE openid = 'dev-openid-001' LIMIT 1);

UPDATE customer SET deposit_balance=300, total_orders=56, total_consumption=1120, avg_cycle_days=7, tags='VIP,测试', first_order_time='2024-06-01', last_delivery_time='2026-07-21' WHERE id=@dev_id;

-- ===== 地址（历城区洪家楼片区） =====
DELETE FROM address WHERE customer_id = @dev_id;
INSERT INTO address (customer_id, name, phone, is_default, label, detail, tag, lat, lng) VALUES
(@dev_id, '测试用户', '13800138000', 1, '家', '济南市历城区花园路洪楼广场小区6号楼', '小区', 36.6865, 117.0617),
(@dev_id, '测试用户', '13800138000', 0, '公司', '济南市历城区华龙路历城科技大厦', '写字楼', 36.6802, 117.0705);

SET @ah = (SELECT id FROM address WHERE customer_id=@dev_id AND is_default=1 LIMIT 1);
SET @aw = (SELECT id FROM address WHERE customer_id=@dev_id AND is_default=0 LIMIT 1);

-- ===== 水票 =====
DELETE FROM ticket_account WHERE customer_id = @dev_id;
INSERT INTO ticket_account (customer_id, water_type_id, remain_quantity) VALUES
(@dev_id, 1, 8), (@dev_id, 4, 5), (@dev_id, 6, 3);

DELETE FROM ticket_record WHERE customer_id = @dev_id;
INSERT INTO ticket_record (customer_id, water_type_id, increase_qty, decrease_qty, source, ticket_source, create_time) VALUES
(@dev_id, 1, 10, 0, 'purchase', 1, DATE_SUB(NOW(), INTERVAL 30 DAY)),
(@dev_id, 1, 0, 2, 'consume', NULL, DATE_SUB(NOW(), INTERVAL 25 DAY)),
(@dev_id, 4, 5, 0, 'purchase', 1, DATE_SUB(NOW(), INTERVAL 20 DAY)),
(@dev_id, 1, 0, 2, 'consume', NULL, DATE_SUB(NOW(), INTERVAL 15 DAY)),
(@dev_id, 6, 3, 0, 'gift', 3, DATE_SUB(NOW(), INTERVAL 10 DAY)),
(@dev_id, 4, 0, 1, 'consume', NULL, DATE_SUB(NOW(), INTERVAL 5 DAY));

-- ===== 水桶 =====
DELETE FROM barrel_record WHERE customer_id = @dev_id;
INSERT INTO barrel_record (customer_id, quantity, status, deposit_refund, note, handle_note, create_time, handle_time) VALUES
(@dev_id, 2, 1, 0, '借桶', NULL, DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 60 DAY)),
(@dev_id, 1, 3, -15, '退桶', '已退1个桶', DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 28 DAY)),
(@dev_id, 2, 1, 0, '借桶', NULL, DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY));

-- ===== 押金 =====
DELETE FROM deposit_record WHERE customer_id = @dev_id;
INSERT INTO deposit_record (customer_id, type, amount, note, create_time) VALUES
(@dev_id, 1, 30, '水桶押金x2', DATE_SUB(NOW(), INTERVAL 60 DAY)),
(@dev_id, 2, 15, '退桶退押金', DATE_SUB(NOW(), INTERVAL 28 DAY)),
(@dev_id, 1, 30, '水桶押金x2', DATE_SUB(NOW(), INTERVAL 15 DAY));

-- ===== 常用订单模板 =====
DELETE FROM order_template_item WHERE template_id IN (SELECT id FROM order_template WHERE customer_id=@dev_id);
DELETE FROM order_template WHERE customer_id = @dev_id;
INSERT INTO order_template (customer_id, name, water_type_id, quantity, address_id, special_note, is_default, enabled) VALUES
(@dev_id, '日常用水-农夫山泉', 1, 2, @ah, '放门口', 1, 1),
(@dev_id, '日常用水-娃哈哈', 4, 1, @ah, '放门口', 0, 1),
(@dev_id, '公司用水', 1, 3, @aw, '送到前台', 0, 1);

-- ===== 订单（10个，各种状态） =====
DELETE FROM orders WHERE customer_id = @dev_id;
INSERT INTO orders (customer_id, address_id, water_type_id, quantity, source, status, payment_status, payment_method, settlement_status, special_note, station_id, create_time, update_time) VALUES
(@dev_id, @ah, 1, 2, 1, 4, 2, 1, 2, '放门口', 1, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(@dev_id, @ah, 4, 1, 1, 3, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
(@dev_id, @aw, 1, 3, 1, 4, 2, 1, 2, '送到前台', 1, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY)),
(@dev_id, @ah, 1, 2, 1, 2, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
(@dev_id, @ah, 6, 1, 1, 2, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 14 DAY), DATE_SUB(NOW(), INTERVAL 13 DAY)),
(@dev_id, @ah, 1, 2, 1, 2, 2, 1, 2, '放门口', 1, DATE_SUB(NOW(), INTERVAL 21 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
(@dev_id, @aw, 4, 2, 1, 2, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 28 DAY), DATE_SUB(NOW(), INTERVAL 27 DAY)),
(@dev_id, @ah, 1, 2, 1, 2, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 35 DAY), DATE_SUB(NOW(), INTERVAL 34 DAY)),
(@dev_id, @ah, 1, 2, 1, 3, 3, 1, 3, '已取消', 1, DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 40 DAY)),
(@dev_id, @ah, 1, 2, 1, 2, 2, 1, 2, '', 1, DATE_SUB(NOW(), INTERVAL 45 DAY), DATE_SUB(NOW(), INTERVAL 44 DAY));

-- ===== 支付记录 =====
DELETE FROM payment_record WHERE customer_id = @dev_id;
INSERT INTO payment_record (order_id, customer_id, amount, payment_method, status)
SELECT id, customer_id, quantity * (SELECT price FROM water_type WHERE id=o.water_type_id), 1, CASE WHEN status=3 THEN 3 ELSE 2 END
FROM orders o WHERE customer_id = @dev_id;

-- ===== 验证 =====
SELECT '=== 测试账号数据 ===' AS info;
SELECT id, name, phone, openid, deposit_balance, total_orders, tags FROM customer WHERE openid='dev-openid-001';
SELECT COUNT(*) AS addresses FROM address WHERE customer_id=@dev_id;
SELECT COUNT(*) AS orders FROM orders WHERE customer_id=@dev_id;
SELECT SUM(remain_quantity) AS total_tickets FROM ticket_account WHERE customer_id=@dev_id;
SELECT COUNT(*) AS templates FROM order_template WHERE customer_id=@dev_id;
