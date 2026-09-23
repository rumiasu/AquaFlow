-- ============================================================
-- 新用户(id=83) - 模拟真实送水场景，跨度6个月
-- 状态值: 1=待组批 2=配送中 3=已完成 4=已组批 5=已取消
-- ============================================================
SET NAMES utf8mb4;
SET @cid = 83;

-- 先删所有关联数据（按外键依赖顺序）
DELETE FROM payment_record WHERE customer_id = @cid;
DELETE FROM order_template_item WHERE template_id IN (SELECT id FROM order_template WHERE customer_id=@cid);
DELETE FROM order_template WHERE customer_id = @cid;
DELETE FROM orders WHERE customer_id = @cid;
DELETE FROM ticket_record WHERE customer_id = @cid;
DELETE FROM ticket_account WHERE customer_id = @cid;
DELETE FROM barrel_record WHERE customer_id = @cid;
DELETE FROM deposit_record WHERE customer_id = @cid;
DELETE FROM address WHERE customer_id = @cid;

-- ===== 地址（3个） =====
INSERT INTO address (customer_id, name, phone, is_default, label, detail, tag, lat, lng) VALUES
(@cid, '张明', '17852822833', 1, '家', '淄博市张店区和平街道阳光花园小区3号楼2单元502', '阳光花园', 36.8155, 118.0382),
(@cid, '张明', '17852822833', 0, '公司', '淄博市张店区柳泉路世贸中心A座1205室', '世贸中心', 36.8088, 118.0512),
(@cid, '张明', '17852822833', 0, '父母家', '淄博市张店区公园街道翠竹园5号楼101', '翠竹园', 36.8201, 118.0445);

SET @ah = (SELECT id FROM address WHERE customer_id=@cid AND label='家' LIMIT 1);
SET @aw = (SELECT id FROM address WHERE customer_id=@cid AND label='公司' LIMIT 1);
SET @ap = (SELECT id FROM address WHERE customer_id=@cid AND label='父母家' LIMIT 1);

-- ===== 水票账户 =====
INSERT INTO ticket_account (customer_id, water_type_id, remain_quantity) VALUES
(@cid, 1, 3),
(@cid, 4, 0);

-- ===== 水票记录 =====
INSERT INTO ticket_record (customer_id, water_type_id, increase_qty, decrease_qty, source, ticket_source, create_time) VALUES
(@cid, 1, 10, 0, 'purchase', 1, '2026-02-15 10:30:00'),
(@cid, 1, 0, 2, 'consume', NULL, '2026-03-05 14:20:00'),
(@cid, 1, 0, 2, 'consume', NULL, '2026-03-22 09:15:00'),
(@cid, 1, 0, 3, 'consume', NULL, '2026-04-18 16:00:00'),
(@cid, 4, 5, 0, 'purchase', 1, '2026-04-25 11:00:00'),
(@cid, 1, 5, 0, 'purchase', 1, '2026-05-10 08:45:00'),
(@cid, 4, 0, 2, 'consume', NULL, '2026-05-20 13:30:00'),
(@cid, 1, 0, 2, 'consume', NULL, '2026-06-01 10:00:00'),
(@cid, 3, 2, 0, 'gift', 3, '2026-06-15 09:00:00'),
(@cid, 4, 0, 3, 'consume', NULL, '2026-06-28 15:20:00'),
(@cid, 1, 0, 5, 'consume', NULL, '2026-07-10 11:30:00'),
(@cid, 1, 5, 0, 'purchase', 1, '2026-07-15 10:00:00'),
(@cid, 1, 0, 3, 'consume', NULL, '2026-07-18 14:00:00');

-- ===== 水桶退桶记录（只保留合理的退桶申请） =====
-- 历史：曾经退过2桶(已退押金)，最近申请退1桶(待处理)
INSERT INTO barrel_record (customer_id, quantity, status, deposit_refund, note, handle_note, create_time, handle_time) VALUES
(@cid, 2, 3, 60.00, '搬家退2桶', '确认退回，已退押金', '2026-04-10 14:00:00', '2026-04-11 09:00:00'),
(@cid, 1, 1, 0, '退1个桶', NULL, '2026-07-18 10:00:00', NULL);

-- ===== 押金记录 =====
INSERT INTO deposit_record (customer_id, type, amount, note, create_time) VALUES
(@cid, 1, 60, '水桶押金(2桶)', '2026-01-10 09:00:00'),
(@cid, 1, 30, '加借1桶押金', '2026-02-20 10:30:00'),
(@cid, 2, 60, '搬家退2桶退押金', '2026-04-11 09:00:00'),
(@cid, 1, 30, '补桶押金', '2026-05-15 08:00:00');

-- ===== 常用订单模板 =====
INSERT INTO order_template (customer_id, name, water_type_id, quantity, address_id, special_note, is_default, enabled) VALUES
(@cid, '家里农夫山泉x2', 1, 2, @ah, '放门口鞋柜旁，不用敲门', 1, 1),
(@cid, '公司娃哈哈x1', 4, 1, @aw, '送到前台，谢谢', 0, 1),
(@cid, '给爸妈送景田', 3, 1, @ap, '送到厨房，老人接电话', 0, 1);

-- ===== 订单 =====
-- delivery_bucket_qty: 已完成订单送出的桶数(=quantity)
-- return_bucket_qty: 已完成订单回收的桶数(=quantity，送水时空桶回收)
INSERT INTO orders (customer_id, address_id, water_type_id, quantity, source, status, payment_status, payment_method, settlement_status, delivery_bucket_qty, return_bucket_qty, special_note, station_id, create_time, update_time) VALUES
-- 1月份（刚注册，试单）- 已完成
(@cid, @ah, 1, 1, 1, 3, 2, 1, 2, 1, 1, '先送一桶试试', 1, '2026-01-10 09:30:00', '2026-01-10 14:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-01-18 10:15:00', '2026-01-18 15:30:00'),
(@cid, @ap, 1, 1, 1, 3, 2, 1, 2, 1, 1, '爸妈家，到了打电话', 1, '2026-01-25 08:00:00', '2026-01-25 11:00:00'),
-- 2月份（春节前后）- 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '春节前多备点', 1, '2026-02-05 09:00:00', '2026-02-05 14:00:00'),
(@cid, @ap, 1, 2, 1, 3, 2, 1, 2, 2, 2, '给爸妈送过年水', 1, '2026-02-06 09:30:00', '2026-02-06 15:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁', 1, '2026-02-15 11:00:00', '2026-02-15 16:00:00'),
(@cid, @ah, 4, 1, 1, 3, 2, 1, 2, 1, 1, '换娃哈哈试试', 1, '2026-02-22 10:00:00', '2026-02-22 14:30:00'),
(@cid, @aw, 1, 1, 1, 3, 2, 1, 2, 1, 1, '公司用', 1, '2026-02-28 14:00:00', '2026-02-28 17:00:00'),
-- 3月份 - 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-03-05 09:00:00', '2026-03-05 13:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁，不用敲门', 1, '2026-03-12 10:30:00', '2026-03-12 15:00:00'),
(@cid, @aw, 4, 1, 1, 3, 2, 1, 2, 1, 1, '送到前台', 1, '2026-03-18 08:30:00', '2026-03-18 11:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '', 1, '2026-03-22 09:15:00', '2026-03-22 14:00:00'),
(@cid, @ap, 3, 1, 1, 3, 2, 1, 2, 1, 1, '给爸妈换景田试试', 1, '2026-03-28 10:00:00', '2026-03-28 14:30:00'),
-- 3月取消1单
(@cid, @ah, 1, 2, 1, 5, 1, 1, 1, 0, 0, '点错了，取消', 1, '2026-03-15 10:00:00', '2026-03-15 10:05:00'),
-- 4月份 - 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-04-02 09:00:00', '2026-04-02 13:30:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁', 1, '2026-04-08 10:00:00', '2026-04-08 15:00:00'),
(@cid, @aw, 4, 2, 1, 3, 2, 1, 2, 2, 2, '公司急用，快点送', 1, '2026-04-14 08:00:00', '2026-04-14 10:30:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '', 1, '2026-04-18 16:00:00', '2026-04-19 09:00:00'),
(@cid, @ap, 1, 1, 1, 3, 2, 1, 2, 1, 1, '到了打电话，老人耳背多按几次门铃', 1, '2026-04-25 09:30:00', '2026-04-25 14:00:00'),
-- 5月份 - 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-05-02 09:00:00', '2026-05-02 13:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁不用敲门', 1, '2026-05-08 10:30:00', '2026-05-08 15:00:00'),
(@cid, @aw, 4, 1, 1, 3, 2, 1, 2, 1, 1, '前台', 1, '2026-05-15 08:30:00', '2026-05-15 11:00:00'),
(@cid, @ah, 1, 3, 1, 3, 2, 1, 2, 3, 3, '多送一桶，天热了喝的多', 1, '2026-05-20 09:00:00', '2026-05-20 14:00:00'),
(@cid, @ap, 3, 1, 1, 3, 2, 1, 2, 1, 1, '爸妈家', 1, '2026-05-28 10:00:00', '2026-05-28 14:30:00'),
-- 5月取消1单
(@cid, @aw, 4, 1, 1, 5, 1, 1, 1, 0, 0, '公司放假了不用送', 1, '2026-05-01 08:00:00', '2026-05-01 08:30:00'),
-- 6月份（夏天用水增加）- 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-06-02 08:30:00', '2026-06-02 12:00:00'),
(@cid, @ah, 1, 3, 1, 3, 2, 1, 2, 3, 3, '天热多备点', 1, '2026-06-08 09:00:00', '2026-06-08 13:00:00'),
(@cid, @aw, 4, 2, 1, 3, 2, 1, 2, 2, 2, '公司夏天喝的多', 1, '2026-06-15 08:00:00', '2026-06-15 10:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁', 1, '2026-06-22 10:00:00', '2026-06-22 14:30:00'),
(@cid, @ap, 1, 2, 1, 3, 2, 1, 2, 2, 2, '给爸妈也送农夫山泉', 1, '2026-06-28 09:30:00', '2026-06-28 14:00:00'),
-- 7月份（最近）- 已完成
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口', 1, '2026-07-05 09:00:00', '2026-07-05 13:00:00'),
(@cid, @ah, 1, 2, 1, 3, 2, 1, 2, 2, 2, '放门口鞋柜旁不用敲门', 1, '2026-07-12 10:00:00', '2026-07-12 14:30:00'),
-- 当前进行中的订单
(@cid, @ah, 1, 2, 1, 2, 2, 1, 2, 2, 0, '放门口', 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(@cid, @aw, 4, 1, 1, 1, 1, 1, 1, 0, 0, '送到前台谢谢', 1, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- ===== 支付记录（匹配订单） =====
INSERT INTO payment_record (order_id, customer_id, amount, water_amount, barrel_deposit, excess_barrels, payment_method, ticket_water_type_id, ticket_qty, status, note, create_time)
SELECT 
    o.id, o.customer_id, 
    CASE WHEN o.status=5 THEN 0 ELSE o.quantity * wt.price END,
    CASE WHEN o.status=5 THEN 0 ELSE o.quantity * wt.price END,
    0, 0,
    o.payment_method, NULL, NULL,
    CASE WHEN o.status=5 THEN 3 ELSE 2 END,
    NULL, o.create_time
FROM orders o
JOIN water_type wt ON o.water_type_id = wt.id
WHERE o.customer_id = @cid;

-- ===== 更新客户统计（基于实际订单计算） =====
UPDATE customer SET
  total_orders = (SELECT COUNT(*) FROM orders WHERE customer_id=@cid AND status=3),
  total_consumption = (SELECT IFNULL(SUM(p.amount),0) FROM payment_record p INNER JOIN orders o ON p.order_id=o.id WHERE o.customer_id=@cid AND o.status=3 AND p.status=2),
  avg_cycle_days = (SELECT CASE WHEN COUNT(*)>1 THEN ROUND(DATEDIFF(MAX(create_time),MIN(create_time))/(COUNT(*)-1)) ELSE NULL END FROM orders WHERE customer_id=@cid AND status=3),
  first_order_time = (SELECT MIN(create_time) FROM orders WHERE customer_id=@cid AND status=3),
  last_delivery_time = (SELECT MAX(update_time) FROM orders WHERE customer_id=@cid AND status=3),
  deposit_balance = 90,
  tags = '老用户,高频'
WHERE id = @cid;

-- ===== 验证 =====
SELECT '=== 真实送水数据注入完成 ===' AS info;
SELECT id, name, phone, deposit_balance, total_orders, total_consumption, avg_cycle_days, tags FROM customer WHERE id=83;
SELECT COUNT(*) AS '地址数' FROM address WHERE customer_id=83;
SELECT COUNT(*) AS '订单数' FROM orders WHERE customer_id=83;
SELECT '--- 订单状态分布 ---' AS info;
SELECT CASE status WHEN 1 THEN '待组批' WHEN 2 THEN '配送中' WHEN 3 THEN '已完成' WHEN 4 THEN '已组批' WHEN 5 THEN '已取消' END AS '状态', COUNT(*) AS cnt FROM orders WHERE customer_id=83 GROUP BY status;
SELECT '--- 水桶统计 ---' AS info;
SELECT IFNULL(SUM(delivery_bucket_qty),0) AS '累计送出', IFNULL(SUM(return_bucket_qty),0) AS '累计回收' FROM orders WHERE customer_id=83 AND status=3;
SELECT IFNULL(SUM(quantity),0) AS '待退桶数' FROM barrel_record WHERE customer_id=83 AND status=1;
SELECT IFNULL(SUM(quantity),0) AS '已退桶数' FROM barrel_record WHERE customer_id=83 AND status IN (2,3);
