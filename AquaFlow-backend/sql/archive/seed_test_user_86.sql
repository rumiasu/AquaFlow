-- ============================================================
-- 测试用户(id=86) 真实数据重建 - 覆盖用户端/配送端/站长端全流程
-- 状态值: 订单 1=待组批 2=配送中 3=已完成 4=已组批 5=已取消
--         批次 1=待出发 2=配送中 3=已完成
--         支付 1=待支付 2=已支付 3=已退款
--         支付方式 1=微信 2=现金 3=水票 4=挂账
-- 桶押金单价: 30元/桶
-- 时间线: 2026-06-01 ~ 2026-08-15
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET @cid = 86;

-- ============================================================
-- 0. 清理该客户全部关联数据（按外键依赖顺序）
-- ============================================================
DELETE FROM order_image WHERE order_id IN (SELECT id FROM orders WHERE customer_id = @cid);
DELETE FROM batch_order WHERE order_id IN (SELECT id FROM orders WHERE customer_id = @cid);
DELETE FROM payment_record WHERE customer_id = @cid;
DELETE FROM orders WHERE customer_id = @cid;
DELETE FROM ticket_record WHERE customer_id = @cid;
DELETE FROM ticket_account WHERE customer_id = @cid;
DELETE FROM deposit_record WHERE customer_id = @cid;
DELETE FROM barrel_record WHERE customer_id = @cid;
DELETE FROM order_template_item WHERE template_id IN (SELECT id FROM order_template WHERE customer_id = @cid);
DELETE FROM order_template WHERE customer_id = @cid;
DELETE FROM address WHERE customer_id = @cid;

-- ============================================================
-- 1. 地址（2个：家 + 公司）
-- ============================================================
INSERT INTO address (id, customer_id, name, phone, is_default, label, detail, tag, lat, lng, create_time, update_time) VALUES
(91, @cid, '测试用户', '13800138000', 1, '家',   '济南市历城区花园路洪楼广场小区6号楼2单元502', '小区', 36.6801, 117.0892, '2026-06-01 09:00:00', '2026-06-01 09:00:00'),
(92, @cid, '测试用户', '13800138000', 0, '公司', '济南市历城区华龙路历城科技大厦B座1205室',   '写字楼', 36.6705, 117.0968, '2026-06-10 10:00:00', '2026-06-10 10:00:00');

SET @ah = 91; -- 家
SET @aw = 92; -- 公司

-- ============================================================
-- 2. 常用订单模板
-- ============================================================
INSERT INTO order_template (id, customer_id, name, water_type_id, quantity, address_id, special_note, enabled, is_default, create_time, update_time) VALUES
(28, @cid, '家里农夫山泉x2', 1, 2, @ah, '放门口鞋柜旁，不用敲门', 1, 1, '2026-06-01 09:05:00', '2026-06-01 09:05:00'),
(29, @cid, '公司农夫山泉x1', 1, 1, @aw, '送到前台，谢谢', 1, 0, '2026-06-10 10:05:00', '2026-06-10 10:05:00'),
(30, @cid, '家里娃哈哈x1',   4, 1, @ah, '放门口', 1, 0, '2026-07-01 08:30:00', '2026-07-01 08:30:00');

-- ============================================================
-- 3. 水票账户 + 水票流水（保持一致）
--    农夫山泉(1): +10 -2 -3 +5 -2 +3 = 11
--    娃哈哈(4):   +5 -1 = 4
-- ============================================================
INSERT INTO ticket_account (id, customer_id, water_type_id, remain_quantity) VALUES
(41, @cid, 1, 11),
(42, @cid, 4, 4);

-- 订单号映射：
-- 801 06-15 农夫山泉x2 水票2张
-- 804 07-10 农夫山泉x3 水票3张
-- 806 07-23 娃哈哈x1   水票1张
-- 808 08-01 农夫山泉x2 水票2张
INSERT INTO ticket_record (customer_id, water_type_id, increase_qty, decrease_qty, order_id, source, ticket_source, create_time) VALUES
(@cid, 1, 10, 0, NULL, 'purchase', 1, '2026-06-05 09:10:00'),
(@cid, 1, 0, 2, 801,   'consume',  NULL, '2026-06-15 14:30:00'),
(@cid, 4, 5, 0, NULL, 'purchase', 1, '2026-07-01 08:40:00'),
(@cid, 1, 0, 3, 804,   'consume',  NULL, '2026-07-10 15:00:00'),
(@cid, 4, 0, 1, 806,   'consume',  NULL, '2026-07-16 16:20:00'),
(@cid, 1, 5, 0, NULL, 'purchase', 1, '2026-07-20 10:30:00'),
(@cid, 1, 0, 2, 808,   'consume',  NULL, '2026-08-01 14:10:00'),
(@cid, 1, 3, 0, NULL, 'purchase', 1, '2026-08-14 14:49:19');

-- ============================================================
-- 4. 押金流水 + 退桶记录（保持一致）
--    押金：开户+60(2桶)，退1桶-30 => 余额30
--    桶账：累计送出16、回收14 => 持有2；已退2(确认1+确认1...实际1) => 实际1
-- ============================================================
INSERT INTO deposit_record (customer_id, type, amount, note, create_time) VALUES
(@cid, 1, 60, '开户押金(2桶)', '2026-06-01 09:02:00'),
(@cid, 2, 30, '退1桶退押金',   '2026-07-20 11:00:00');

INSERT INTO barrel_record (customer_id, quantity, status, deposit_refund, note, handle_note, create_time, handle_time) VALUES
(@cid, 1, 3, 30.00, '退1个桶', '确认退回，已退押金', '2026-07-20 10:30:00', '2026-07-20 11:00:00'),
(@cid, 1, 1, 0,     '先退1个桶，押金暂不退', NULL, '2026-08-14 15:00:00', NULL);

-- ============================================================
-- 5. 订单（12单：9完成 1配送中 1已组批 1待组批；另含1取消）
--    delivery_bucket_qty = 送出桶数; return_bucket_qty = 回收桶数
-- ============================================================
-- 已完成（9单，均含配送员/批次）
INSERT INTO orders (id, customer_id, address_id, water_type_id, quantity, source, status, payment_status, payment_method, settlement_status,
                    delivery_bucket_qty, return_bucket_qty, delivery_staff_id, guard_info, delivery_time_request, special_note,
                    receiver_name, receiver_phone, address_snapshot, factory_id, station_id,
                    barrel_discrepancy, barrel_discrepancy_note, create_time, update_time) VALUES
-- 800 06-05 首单：农夫山泉x2 家 微信在线支付 已完成（首次送桶2个，回收0）
(800, @cid, @ah, 1, 2, 3, 3, 2, 1, 2, 2, 0, 4, NULL, '上午送达', '首单，放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 2, '首次配送，空桶留在客户处', '2026-06-05 09:30:00', '2026-06-05 14:00:00'),
-- 801 06-15 农夫山泉x2 家 水票2张 已完成
(801, @cid, @ah, 1, 2, 3, 3, 2, 3, 2, 2, 2, 5, NULL, '上午送达', '放门口鞋柜旁', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-06-15 09:10:00', '2026-06-15 14:30:00'),
-- 802 06-22 农夫山泉x2 家 货到付款(现金) 已完成
(802, @cid, @ah, 1, 2, 3, 3, 2, 2, 2, 2, 2, 4, NULL, NULL, '放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-06-22 09:20:00', '2026-06-22 15:00:00'),
-- 803 06-30 农夫山泉x1 公司 微信 已完成
(803, @cid, @aw, 1, 1, 3, 3, 2, 1, 2, 1, 1, 6, '门卫张师傅，电话13812345678', '工作日送', '送到前台，谢谢', '测试用户', '13800138000', '济南市历城区华龙路历城科技大厦B座1205室', 1, 1, 0, NULL, '2026-06-30 08:40:00', '2026-06-30 11:30:00'),
-- 804 07-10 农夫山泉x3 家 水票3张 已完成（夏天加量）
(804, @cid, @ah, 1, 3, 3, 3, 2, 3, 2, 3, 3, 4, NULL, '尽快送', '天热多备点', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-07-10 09:00:00', '2026-07-10 15:00:00'),
-- 805 07-16 娃哈哈x1 家 现金 已完成
(805, @cid, @ah, 4, 1, 3, 3, 2, 2, 2, 1, 1, 5, NULL, NULL, '换娃哈哈试试', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-07-16 09:15:00', '2026-07-16 16:20:00'),
-- 806 07-23 娃哈哈x1 家 水票1张 已完成
(806, @cid, @ah, 4, 1, 3, 3, 2, 3, 2, 1, 1, 6, NULL, NULL, '放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-07-23 09:30:00', '2026-07-23 14:00:00'),
-- 807 07-31 农夫山泉x2 家 微信 已完成
(807, @cid, @ah, 1, 2, 3, 3, 2, 1, 2, 2, 2, 6, NULL, NULL, '放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-07-31 09:30:00', '2026-07-31 14:00:00'),
-- 808 08-01 农夫山泉x2 家 水票2张 已完成
(808, @cid, @ah, 1, 2, 3, 3, 2, 3, 2, 2, 2, 4, NULL, '上午送达', '放门口鞋柜旁', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-08-01 09:05:00', '2026-08-01 14:10:00'),
-- 809 08-08 农夫山泉x1 公司 现金 已完成
(809, @cid, @aw, 1, 1, 3, 3, 2, 2, 2, 1, 1, 5, '门卫张师傅，电话13812345678', '工作日送', '送到前台，谢谢', '测试用户', '13800138000', '济南市历城区华龙路历城科技大厦B座1205室', 1, 1, 0, NULL, '2026-08-08 08:30:00', '2026-08-08 11:00:00'),
-- 810 08-11 农夫山泉x2 家 微信 在线已付 配送中（配送员4李永强已接单）
(810, @cid, @ah, 1, 2, 3, 2, 2, 1, 1, 2, 0, 4, NULL, '尽快送', '放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-08-11 09:00:00', '2026-08-11 09:00:00'),
-- 811 08-13 农夫山泉x3 公司 货到付款 已组批待出发（批次待出发，配给配送员6）
(811, @cid, @aw, 1, 3, 3, 4, 1, 2, 1, 0, 0, NULL, '门卫张师傅，电话13812345678', '工作日送', '送到前台，谢谢', '测试用户', '13800138000', '济南市历城区华龙路历城科技大厦B座1205室', 1, 1, 0, NULL, '2026-08-13 08:30:00', '2026-08-13 08:30:00'),
-- 812 08-15 农夫山泉x2 家 微信 在线已付 待组批（站长端今日可组批）
(812, @cid, @ah, 1, 2, 3, 1, 2, 1, 1, 0, 0, NULL, NULL, NULL, '放门口', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, NULL, '2026-08-15 09:00:00', '2026-08-15 09:00:00');

-- 已取消（1单）：07-05 下单后当天取消，微信支付已退款
INSERT INTO orders (id, customer_id, address_id, water_type_id, quantity, source, status, payment_status, payment_method, settlement_status,
                    delivery_bucket_qty, return_bucket_qty, delivery_staff_id, special_note,
                    receiver_name, receiver_phone, address_snapshot, factory_id, station_id,
                    barrel_discrepancy, create_time, update_time) VALUES
(813, @cid, @ah, 1, 2, 3, 5, 1, 1, 1, 0, 0, NULL, '临时有事，取消订单', '测试用户', '13800138000', '济南市历城区花园路洪楼广场小区6号楼2单元502', 1, 1, 0, '2026-07-05 09:00:00', '2026-07-05 09:10:00');

-- ============================================================
-- 6. 批次 + 批次-订单关联（已完成批次状态3，在途批次1/2）
--    批次成员均为测试用户订单，total_qty = 各订单数量之和
-- ============================================================
INSERT INTO batch (id, status, station_id, delivery_person_id, total_qty, create_time, update_time) VALUES
(200, 3, 1, 4, 4, '2026-06-05 09:30:00', '2026-06-05 14:00:00'),  -- 订单800,801
(201, 3, 1, 5, 3, '2026-06-22 09:20:00', '2026-06-30 11:30:00'),  -- 订单802,803
(202, 3, 1, 4, 4, '2026-07-10 09:00:00', '2026-07-10 15:00:00'),  -- 订单804,805
(203, 3, 1, 6, 3, '2026-07-23 09:30:00', '2026-07-23 14:00:00'),  -- 订单806,807
(204, 3, 1, 4, 3, '2026-08-01 09:05:00', '2026-08-08 11:00:00'),  -- 订单808,809
(205, 2, 1, 4, 2, '2026-08-11 09:00:00', '2026-08-11 09:00:00'),  -- 订单810 配送中
(206, 1, 1, 6, 3, '2026-08-13 08:30:00', '2026-08-13 08:30:00');  -- 订单811 待出发

INSERT INTO batch_order (batch_id, order_id, create_time) VALUES
(200, 800, '2026-06-05 09:30:00'), (200, 801, '2026-06-15 09:10:00'),
(201, 802, '2026-06-22 09:20:00'), (201, 803, '2026-06-30 08:40:00'),
(202, 804, '2026-07-10 09:00:00'), (202, 805, '2026-07-16 09:15:00'),
(203, 806, '2026-07-23 09:30:00'), (203, 807, '2026-07-31 09:30:00'),
(204, 808, '2026-08-01 09:05:00'), (204, 809, '2026-08-08 08:30:00'),
(205, 810, '2026-08-11 09:00:00'),
(206, 811, '2026-08-13 08:30:00');

-- ============================================================
-- 7. 支付记录（订单支付 + 水票直购支付）
--    已取消订单813：支付记录状态3(已退款)
-- ============================================================
INSERT INTO payment_record (order_id, customer_id, amount, water_amount, barrel_deposit, excess_barrels, payment_method, ticket_water_type_id, ticket_qty, status, note, create_time) VALUES
-- 订单支付（金额 = 数量×单价）
(800, @cid, 40.00, 40.00, 0.00, 0, 1, NULL, NULL, 2, NULL, '2026-06-05 09:30:00'),
(801, @cid, 40.00, 40.00, 0.00, 0, 3, 1, 2, 2, '水票支付', '2026-06-15 09:10:00'),
(802, @cid, 40.00, 40.00, 0.00, 0, 2, NULL, NULL, 2, '货到付款-现金', '2026-06-22 09:20:00'),
(803, @cid, 20.00, 20.00, 0.00, 0, 1, NULL, NULL, 2, NULL, '2026-06-30 08:40:00'),
(804, @cid, 60.00, 60.00, 0.00, 0, 3, 1, 3, 2, '水票支付', '2026-07-10 09:00:00'),
(805, @cid, 15.00, 15.00, 0.00, 0, 2, NULL, NULL, 2, '货到付款-现金', '2026-07-16 09:15:00'),
(806, @cid, 15.00, 15.00, 0.00, 0, 3, 4, 1, 2, '水票支付', '2026-07-24 09:30:00'),
(807, @cid, 40.00, 40.00, 0.00, 0, 1, NULL, NULL, 2, NULL, '2026-07-24 09:30:00'),
(808, @cid, 40.00, 40.00, 0.00, 0, 3, 1, 2, 2, '水票支付', '2026-08-01 09:05:00'),
(809, @cid, 20.00, 20.00, 0.00, 0, 2, NULL, NULL, 2, '货到付款-现金', '2026-08-08 08:30:00'),
(810, @cid, 40.00, 40.00, 0.00, 0, 1, NULL, NULL, 2, NULL, '2026-08-11 09:00:00'),
(811, @cid, 60.00, 60.00, 0.00, 0, 2, NULL, NULL, 1, '货到付款(待收款)', '2026-08-13 08:30:00'),
(812, @cid, 40.00, 40.00, 0.00, 0, 1, NULL, NULL, 2, NULL, '2026-08-15 09:00:00'),
(813, @cid, 40.00, 40.00, 0.00, 0, 1, NULL, NULL, 3, '订单取消自动退款', '2026-07-05 09:00:00'),
-- 水票直购支付（无订单）
(NULL, @cid, 200.00, 200.00, 0.00, 0, 1, 1, 10, 2, '线上购买水票', '2026-06-05 09:10:00'),
(NULL, @cid, 75.00, 75.00, 0.00, 0, 1, 4, 5, 2, '线上购买水票', '2026-07-01 08:40:00'),
(NULL, @cid, 100.00, 100.00, 0.00, 0, 1, 1, 5, 2, '线上购买水票', '2026-07-20 10:30:00'),
(NULL, @cid, 60.00, 60.00, 0.00, 0, 1, 1, 3, 2, '线上购买水票', '2026-08-14 14:49:19');

-- ============================================================
-- 8. 更新客户统计（与订单/支付一致）
--    total_orders=9(完成) total_consumption=315 avg_cycle_days=8
--    first_order_time=2026-06-05 last_delivery_time=2026-08-08
--    deposit_balance=30 tags=老客户,高频
-- ============================================================
UPDATE customer SET
  create_time = '2026-06-01 09:00:00',
  update_time = NOW(),
  station_id = 1,
  customer_type = 1,
  total_orders = (SELECT COUNT(*) FROM orders WHERE customer_id=@cid AND status=3),
  total_consumption = (SELECT IFNULL(SUM(p.amount),0) FROM payment_record p INNER JOIN orders o ON p.order_id=o.id WHERE o.customer_id=@cid AND o.status=3 AND p.status=2),
  avg_cycle_days = (SELECT CASE WHEN COUNT(*)>1 THEN ROUND(DATEDIFF(MAX(create_time),MIN(create_time))/(COUNT(*)-1)) ELSE NULL END FROM orders WHERE customer_id=@cid AND status=3),
  first_order_time = (SELECT MIN(create_time) FROM orders WHERE customer_id=@cid AND status=3),
  last_delivery_time = (SELECT MAX(update_time) FROM orders WHERE customer_id=@cid AND status=3),
  deposit_balance = 30,
  tags = '老客户,高频'
WHERE id = @cid;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 9. 验证
-- ============================================================
SELECT '=== 测试用户86数据重建完成 ===' AS info;
SELECT id, name, phone, station_id, deposit_balance, total_orders, total_consumption, avg_cycle_days, tags, first_order_time, last_delivery_time FROM customer WHERE id=@cid;
SELECT '--- 订单状态分布 ---' AS info;
SELECT CASE status WHEN 1 THEN '待组批' WHEN 2 THEN '配送中' WHEN 3 THEN '已完成' WHEN 4 THEN '已组批' WHEN 5 THEN '已取消' END AS '状态', COUNT(*) AS cnt FROM orders WHERE customer_id=@cid GROUP BY status;
SELECT '--- 水票账实核对 ---' AS info;
SELECT ta.water_type_id AS '水类型', ta.remain_quantity AS '账户剩余',
       (SELECT IFNULL(SUM(increase_qty),0)-IFNULL(SUM(decrease_qty),0) FROM ticket_record WHERE customer_id=@cid AND water_type_id=ta.water_type_id) AS '流水净额'
FROM ticket_account ta WHERE ta.customer_id=@cid;
SELECT '--- 押金核对 ---' AS info;
SELECT @cid AS '客户', deposit_balance AS '客户余额',
       (SELECT IFNULL(SUM(CASE WHEN type=1 THEN amount WHEN type=2 THEN -amount ELSE 0 END),0) FROM deposit_record WHERE customer_id=@cid) AS '流水净额'
FROM customer WHERE id=@cid;
SELECT '--- 桶账核对 ---' AS info;
SELECT IFNULL(SUM(delivery_bucket_qty),0) AS '累计送出', IFNULL(SUM(return_bucket_qty),0) AS '累计回收',
       IFNULL(SUM(delivery_bucket_qty),0)-IFNULL(SUM(return_bucket_qty),0) AS '持有桶数'
FROM orders WHERE customer_id=@cid AND status=3;
SELECT IFNULL(SUM(quantity),0) AS '待退桶数' FROM barrel_record WHERE customer_id=@cid AND status=1;
SELECT IFNULL(SUM(quantity),0) AS '已退桶数' FROM barrel_record WHERE customer_id=@cid AND status IN (2,3);
SELECT '--- 批次核对 ---' AS info;
SELECT b.id AS '批次', b.status AS '批次状态', b.delivery_person_id AS '配送员', b.total_qty AS '总数量',
       (SELECT COUNT(*) FROM batch_order bo WHERE bo.batch_id=b.id) AS '订单数'
FROM batch b WHERE b.id BETWEEN 200 AND 206 ORDER BY b.id;
SELECT '--- 无孤儿订单/批次关联 ---' AS info;
SELECT COUNT(*) AS '未关联批次的非待组批订单' FROM orders WHERE customer_id=@cid AND status IN (2,3,4) AND id NOT IN (SELECT order_id FROM batch_order);
