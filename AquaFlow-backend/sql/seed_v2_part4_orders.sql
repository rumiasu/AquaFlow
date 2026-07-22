-- ============================================================
-- AquaFlow 种子数据 v2（Part 4: 存储过程生成200+订单+批次+支付+附属数据）
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
  -- 填充映射（客户id→水站→默认地址→配送员→水厂）
  INSERT INTO tmp_customer_map VALUES
  (1,1,1,5,1,'张伟','13600001001','淄博市张店区联通路68号1号楼301'),
  (2,1,3,5,1,'李娜','13600001002','淄博市张店区华光路58号科技大厦'),
  (3,1,4,6,1,'王强','13600001003','淄博市张店区人民路123号5号楼102'),
  (4,1,5,6,1,'刘洋','13600001004','淄博市张店区柳泉路200号创业中心'),
  (5,1,6,5,1,'陈明','13600001005','淄博市张店区潘庄社区8号楼'),
  (6,1,7,7,1,'杨秀芳','13600001006','淄博市张店区共青团路88号'),
  (8,1,9,6,1,'黄丽华','13600001008','淄博市张店区美食街18号'),
  (9,1,10,7,1,'孙文博','13600001009','淄博市张店区体育路28号'),
  (10,1,11,7,1,'周建国','13600001010','淄博市张店区玉龙湖畔12号'),
  (13,2,13,9,1,'赵磊','13600002001','淄博市淄川区般阳路56号'),
  (14,2,14,9,1,'孙丽','13600002002','淄博市淄川区建材城A区'),
  (15,2,15,10,1,'周杰','13600002003','淄博市淄川区吉祥路88号'),
  (17,2,17,10,1,'钱文华','13600002005','淄博市淄川区教育路1号'),
  (19,2,19,9,1,'陈秀兰','13600002007','淄博市淄川区松龄路养老院'),
  (21,2,21,11,1,'卫国强','13600002009','淄博市淄川区工业园B区'),
  (23,3,23,13,1,'郑浩','13600003001','淄博市博山区白虎山路23号'),
  (24,3,24,13,1,'王秀英','13600003002','淄博市博山区峨嵋山路45号'),
  (25,3,25,14,1,'李明华','13600003003','淄博市博山区中心路100号'),
  (27,3,27,14,1,'许文涛','13600003005','淄博市博山区美食街8号'),
  (30,3,29,13,1,'施明远','13600003008','淄博市博山区中心路188号'),
  (31,4,30,16,1,'陶志强','13600004001','淄博市临淄区齐都路56号'),
  (32,4,31,16,1,'贾文静','13600004002','淄博市临淄区稷下路88号'),
  (34,4,33,17,1,'陈晓明','13600004004','淄博市临淄区牛山路18号'),
  (36,4,35,17,1,'胡建明','13600004006','淄博市临淄区学府路1号'),
  (39,5,37,19,2,'黄涛','13600005001','济南市历下区经十路100号'),
  (40,5,38,19,2,'林静','13600005002','济南市历下区泺源大街68号'),
  (42,5,40,20,2,'胡明','13600005004','济南市历下区泉城路168号'),
  (43,5,41,20,2,'朱建华','13600005005','济南市历下区旅游路88号'),
  (44,5,42,21,2,'高文静','13600005006','济南市历下区解放路118号'),
  (46,5,44,21,2,'马文博','13600005008','济南市历下区黑虎泉西路18号'),
  (49,6,46,23,2,'高磊','13600006001','济南市槐荫区经十路200号'),
  (50,6,47,23,2,'马丽','13600006002','济南市槐荫区经二路88号'),
  (53,6,49,24,2,'沈志强','13600006005','济南市槐荫区张庄路66号'),
  (55,6,51,24,2,'冯秀芳','13600006007','济南市槐荫区南辛庄路22号'),
  (57,8,53,28,3,'梁志强','13600007001','青岛市市南区香港中路88号'),
  (58,8,54,28,3,'谢文静','13600007002','青岛市市南区山东路118号'),
  (60,8,56,29,3,'唐秀兰','13600007004','青岛市市南区泰州路33号'),
  (61,8,57,29,3,'龙伟民','13600007005','青岛市市南区闽江路28号'),
  (63,8,59,30,3,'傅明辉','13600007007','青岛市市南区香港中路200号'),
  (65,9,61,32,3,'文志强','13600008001','青岛市崂山区海尔路88号'),
  (66,9,62,32,3,'庞秀芳','13600008002','青岛市崂山区秦岭路66号'),
  (68,9,64,33,3,'范文静','13600008004','青岛市崂山区松岭路1号'),
  (69,9,65,33,3,'苏明辉','13600008005','青岛市崂山区丽海东路56号'),
  (73,10,69,35,3,'钱志明','13600009001','青岛市市北区延吉路120号'),
  (74,10,70,35,3,'韩秀兰','13600009002','青岛市市北区敦化路88号'),
  (76,10,72,36,3,'方文静','13600009004','青岛市市北区人民路1号'),
  (77,10,73,36,3,'邹明辉','13600009005','青岛市市北区嘉定路33号');

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
    IF v_customer_id IN (2,4,8,9,14,17,19,21,25,27,30,32,34,36,40,44,46,50,53,58,61,63,66,68,74,76) THEN
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
      ELSE SET v_pay_method = 4; SET v_settlement = IF(v_customer_id IN (2,14,17,19,21,25,27,30,32,34,36,40,44,46,50,53,58,61,63,66,68,74,76), 1, 2);
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
