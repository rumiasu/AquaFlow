-- ============================================================
-- AquaFlow 种子数据 v2（Part 4: 存储过程生成200+订单+批次+支付）
-- 订单全部归属 洪家楼/唐冶/王舍人 三个历城区水站
-- ============================================================
DROP PROCEDURE IF EXISTS generate_orders;
DELIMITER //
CREATE PROCEDURE generate_orders()
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE v_customer_id INT;
  DECLARE v_station_id INT;
  DECLARE v_address_id INT;
  DECLARE v_water_type_id INT;
  DECLARE v_qty INT;
  DECLARE v_source INT;
  DECLARE v_status INT;
  DECLARE v_pay_status INT;
  DECLARE v_pay_method INT;
  DECLARE v_settlement INT;
  DECLARE v_staff_id INT;
  DECLARE v_factory_id INT;
  DECLARE v_create_date DATETIME;
  DECLARE v_price DECIMAL(10,2);
  DECLARE v_amount DECIMAL(10,2);
  DECLARE v_receiver_name VARCHAR(50);
  DECLARE v_receiver_phone VARCHAR(20);
  DECLARE v_addr_detail VARCHAR(500);
  DECLARE v_order_id INT;
  DECLARE v_batch_id INT;
  DECLARE v_batch_qty INT;
  DECLARE v_delivery_bucket INT;
  DECLARE v_return_bucket INT;

  -- 客户→水站→配送员 映射表（用临时表）
  DROP TEMPORARY TABLE IF EXISTS tmp_customer_map;
  CREATE TEMPORARY TABLE tmp_customer_map (
    cid INT, sid INT, addr INT, staff INT, fid INT,
    cname VARCHAR(50), cphone VARCHAR(20), adetail VARCHAR(500)
  );
  -- 填充映射（客户id→水站→默认地址(与地址表id一致)→配送员→水厂）
  INSERT INTO tmp_customer_map VALUES
  -- 洪家楼水站（station=1）
  (1,1,1,4,1,'张伟','13605310001','济南市历城区洪楼广场小区5号楼'),
  (2,1,2,5,1,'李娜','13605310002','济南市历城区花园路洪楼银座写字楼'),
  (3,1,3,5,1,'王强','13605310003','济南市历城区山大路百花小区3号楼'),
  (4,1,4,6,1,'刘洋','13605310004','济南市历城区华龙路历城科技大厦'),
  (5,1,5,4,1,'陈明','13605310005','济南市历城区二环东路山航宿舍'),
  (6,1,6,6,1,'杨秀芳','13605310006','济南市历城区甸柳新村五区宿舍'),
  (7,1,7,4,1,'赵磊','13605310007','济南市历城区洪家楼西路新龙小区'),
  (8,1,8,5,1,'黄丽华','13605310008','济南市历城区花园路小吃街旺铺'),
  (9,1,9,6,1,'孙文博','13605310009','济南市历城区山大南路火炬健身房'),
  (10,1,10,4,1,'周建国','13605310010','济南市历城区浆水泉路名仕庄园'),
  (12,1,12,6,1,'张明远','13605310012','济南市历城区洪楼印象理发店'),
  (13,1,13,5,1,'刘芳','13605310013','济南市历城区山大中心校区（山东大学）'),
  (14,1,14,4,1,'王秀英','13605310014','济南市历城区洪楼街道乐龄养老院'),
  (15,1,15,5,1,'陈建国','13605310015','济南市历城区祝舜路翡翠明珠小区'),
  -- 唐冶水站（station=2）
  (16,2,16,8,1,'周杰','13605310016','济南市历城区唐冶中路唐城小区'),
  (17,2,17,9,1,'吴芳芳','13605310017','济南市历城区世纪大道银丰唐郡'),
  (18,2,18,10,1,'钱文华','13605310018','济南市历城区彩石街道山东职业学院'),
  (19,2,19,10,1,'冯晓东','13605310019','济南市历城区雪山片区金茂悦小区'),
  (20,2,20,8,1,'陈秀兰','13605310020','济南市历城区唐冶街道康寿老年公寓'),
  (21,2,21,9,1,'褚明辉','13605310021','济南市历城区港沟街道保利花园'),
  (22,2,22,8,1,'卫国强','13605310022','济南市历城区世纪大道智能制造产业园'),
  (23,2,23,9,1,'蒋丽华','13605310023','济南市历城区唐冶中路商业街旺铺'),
  (24,2,24,8,1,'郑浩','13605310024','济南市历城区飞跃大道龙湖春江郦城'),
  (25,2,25,10,1,'王秀梅','13605310025','济南市历城区唐冶西路春天花园'),
  (26,2,26,9,1,'李明华','13605310026','济南市历城区凤鸣路746号融创园写字楼'),
  (27,2,27,8,1,'张秀梅','13605310027','济南市历城区神武北路万科翡翠山语'),
  (28,2,28,10,1,'许文涛','13605310028','济南市历城区唐冶中路晋味居饭店'),
  -- 王舍人水站（station=3）
  (29,3,29,12,1,'何晓东','13605310029','济南市历城区王舍人镇工业北路生活区'),
  (31,3,31,13,1,'施明远','13605310031','济南市历城区济南东站广场商务楼'),
  (32,3,32,12,1,'陶志强','13605310032','济南市历城区王舍人街道杨北社区'),
  (33,3,33,13,1,'贾文静','13605310033','济南市历城区工业北路东160号总部中心'),
  (34,3,34,12,1,'刘强','13605310034','济南市历城区凤鸣路蔚来城小区'),
  (35,3,35,13,1,'陈晓明','13605310035','济南市历城区工业北路便民菜市场'),
  (36,3,36,13,1,'杨帆','13605310036','济南市历城区郭店街道郭店家园'),
  (37,3,37,12,1,'胡建明','13605310037','济南市历城区王舍人街道童乐幼儿园'),
  (38,3,38,13,1,'林秀芳','13605310038','济南市历城区荷花路街道曲家村'),
  (40,3,40,12,1,'曹静','13605310040','济南市历城区济南东站站前街商铺');

  -- 循环生成220条订单
  WHILE i < 220 DO
    -- 随机选一个客户映射
    SELECT cid, sid, addr, staff, fid, cname, cphone, adetail
    INTO v_customer_id, v_station_id, v_address_id, v_staff_id, v_factory_id,
         v_receiver_name, v_receiver_phone, v_addr_detail
    FROM tmp_customer_map ORDER BY RAND() LIMIT 1;

    -- 随机水类型1-5（桶装水为主）
    SET v_water_type_id = FLOOR(1 + RAND() * 5);
    -- 数量：个人1-5，企业5-20
    IF v_customer_id IN (2,4,8,9,12,13,14,18,20,22,26,28,31,33,35,37,40) THEN
      SET v_qty = FLOOR(5 + RAND() * 16);
    ELSE
      SET v_qty = FLOOR(1 + RAND() * 5);
    END IF;
    -- 来源 1=电话 2=微信群 3=小程序
    SET v_source = FLOOR(1 + RAND() * 3);
    -- 价格
    SELECT price INTO v_price FROM water_type WHERE id = v_water_type_id;
    SET v_amount = v_price * v_qty;

    -- 时间分布：70%近30天，20%在30-60天，10%在60-90天
    IF RAND() < 0.7 THEN
      SET v_create_date = DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*30) DAY) - INTERVAL FLOOR(RAND()*12) HOUR;
    ELSEIF RAND() < 0.85 THEN
      SET v_create_date = DATE_SUB(NOW(), INTERVAL FLOOR(30+RAND()*30) DAY) - INTERVAL FLOOR(RAND()*12) HOUR;
    ELSE
      SET v_create_date = DATE_SUB(NOW(), INTERVAL FLOOR(60+RAND()*30) DAY) - INTERVAL FLOOR(RAND()*12) HOUR;
    END IF;

    -- 状态分布：60%已完成，10%配送中，10%已组批，10%待组批，5%已取消，5%当天新单
    SET @r = RAND();
    IF @r < 0.05 THEN
      SET v_status = 5; -- 已取消
      SET v_pay_status = 1; SET v_pay_method = NULL; SET v_settlement = 1;
      SET v_delivery_bucket = 0; SET v_return_bucket = 0;
    ELSEIF @r < 0.15 THEN
      SET v_status = 1; -- 待组批
      SET v_pay_status = 1; SET v_pay_method = NULL; SET v_settlement = 1;
      SET v_delivery_bucket = 0; SET v_return_bucket = 0;
    ELSEIF @r < 0.25 THEN
      SET v_status = 4; -- 已组批（等待出发）
      SET v_pay_status = 1; SET v_pay_method = NULL; SET v_settlement = 1;
      SET v_delivery_bucket = v_qty; SET v_return_bucket = 0;
    ELSEIF @r < 0.35 THEN
      SET v_status = 2; -- 配送中
      SET v_pay_status = 1; SET v_pay_method = NULL; SET v_settlement = 1;
      SET v_delivery_bucket = v_qty; SET v_return_bucket = 0;
    ELSE
      SET v_status = 3; -- 已完成
      SET v_pay_status = 2; SET v_settlement = 1;
      SET v_delivery_bucket = v_qty; SET v_return_bucket = v_qty;
      -- 支付方式：1微信50%，2现金20%，3水票15%，4挂账15%
      SET @pr = RAND();
      IF @pr < 0.5 THEN SET v_pay_method = 1;
      ELSEIF @pr < 0.7 THEN SET v_pay_method = 2;
      ELSEIF @pr < 0.85 THEN SET v_pay_method = 3;
      ELSE SET v_pay_method = 4; SET v_settlement = IF(v_customer_id IN (2,4,8,9,12,13,14,18,20,22,26,28,31,33,35,37,40), 1, 2);
      END IF;
    END IF;

    -- 插入订单
    INSERT INTO orders (customer_id, station_id, address_id, water_type_id, quantity, source,
      status, payment_status, payment_method, settlement_status,
      delivery_bucket_qty, return_bucket_qty, delivery_staff_id, factory_id,
      receiver_name, receiver_phone, address_snapshot, special_note,
      create_time, update_time)
    VALUES (v_customer_id, v_station_id, v_address_id, v_water_type_id, v_qty, v_source,
      v_status, v_pay_status, v_pay_method, v_settlement,
      v_delivery_bucket, v_return_bucket, v_staff_id, v_factory_id,
      v_receiver_name, v_receiver_phone, v_addr_detail,
      ELT(FLOOR(1+RAND()*4), '放门口', '前台签收', '尽快送', NULL),
      v_create_date, IF(v_status=3, DATE_ADD(v_create_date, INTERVAL FLOOR(1+RAND()*3) HOUR), NOW()));

    SET v_order_id = LAST_INSERT_ID();

    -- 已完成的订单生成支付记录
    IF v_status = 3 AND v_pay_method IS NOT NULL THEN
      INSERT INTO payment_record (order_id, customer_id, amount, payment_method, status, create_time, update_time)
      VALUES (v_order_id, v_customer_id, v_amount, v_pay_method, 2,
        DATE_ADD(v_create_date, INTERVAL FLOOR(1+RAND()*2) HOUR), NOW());
    END IF;

    SET i = i + 1;
  END WHILE;

  -- ===== 生成批次（将已组批+配送中+已完成的订单按水站分组打包） =====
  DROP TEMPORARY TABLE IF EXISTS tmp_batch_orders;
  CREATE TEMPORARY TABLE tmp_batch_orders AS
    SELECT id, station_id, status, delivery_staff_id, quantity, create_time,
      ROW_NUMBER() OVER (PARTITION BY station_id ORDER BY id) AS rn
    FROM orders WHERE status IN (2, 3, 4);

  -- 每4个订单一批，按水站分组插入批次
  INSERT INTO batch (status, total_qty, station_id, delivery_person_id, create_time, update_time)
  SELECT
    CASE WHEN MAX(status) = 3 THEN 3 WHEN MAX(status) = 2 THEN 2 ELSE 1 END,
    SUM(quantity),
    station_id,
    MAX(delivery_staff_id),
    MIN(create_time),
    NOW()
  FROM tmp_batch_orders
  GROUP BY station_id, FLOOR((rn - 1) / 4);

  -- 关联批次-订单：按水站+时间匹配
  INSERT INTO batch_order (batch_id, order_id, create_time)
  SELECT b.id, o.id, NOW()
  FROM orders o
  JOIN batch b ON b.station_id = o.station_id
  WHERE o.status IN (2, 3, 4)
    AND o.create_time >= b.create_time
    AND o.create_time <= DATE_ADD(b.create_time, INTERVAL 2 DAY)
  LIMIT 300;

  DROP TEMPORARY TABLE IF EXISTS tmp_customer_map;
  DROP TEMPORARY TABLE IF EXISTS tmp_batch_orders;
END //
DELIMITER ;

CALL generate_orders();
DROP PROCEDURE IF EXISTS generate_orders;