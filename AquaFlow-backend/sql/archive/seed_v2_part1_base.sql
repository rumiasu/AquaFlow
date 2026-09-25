-- ============================================================
-- AquaFlow 完整业务种子数据 v2（Part 1: 基础数据）
-- 1水厂 / 4水站(1停用) / 40客户 / 17员工 / 220订单
-- 业务范围：济南市历城区（水源地+城区配送站）
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM audit_log;
DELETE FROM risk_alert;
DELETE FROM stock_transfer;
DELETE FROM customer_station_record;
DELETE FROM company_info;
DELETE FROM order_template_item;
DELETE FROM order_template;
DELETE FROM barrel_record;
DELETE FROM deposit_record;
DELETE FROM ticket_record;
DELETE FROM ticket_account;
DELETE FROM payment_record;
DELETE FROM batch_order;
DELETE FROM batch;
DELETE FROM orders;
DELETE FROM inventory;
DELETE FROM address;
DELETE FROM customer;
DELETE FROM staff;
DELETE FROM water_type;
DELETE FROM station;
DELETE FROM factory;

-- ===== 1. 水厂（1） =====
INSERT INTO factory (id,name,contact_person,contact_phone,address,status,create_time,update_time) VALUES
(1,'济南泉源饮用水厂','王建国','13905310001','济南市历城区彩石街道旅游路8899号',1,'2024-01-15 08:00:00','2024-01-15 08:00:00');

-- ===== 2. 水站（4，含1个已关闭） =====
INSERT INTO station (id,name,manager,phone,address,factory_id,status,create_time,update_time) VALUES
(1,'洪家楼水站','张建国','13805310001','济南市历城区洪家楼街道花园路118号',1,1,'2024-01-20','2024-01-20'),
(2,'唐冶水站','刘大明','13805310002','济南市历城区唐冶街道世纪大道168号',1,1,'2024-02-01','2024-02-01'),
(3,'王舍人水站','孙丽华','13805310003','济南市历城区王舍人街道工业北路88号',1,1,'2024-02-15','2024-02-15'),
(4,'郭店水站','马国庆','13805310004','济南市历城区郭店街道102省道辅路66号',1,0,'2024-04-01','2025-06-15');

-- ===== 3. 水类型（10） =====
INSERT INTO water_type (id,name,spec,price,deposit,note,create_time,update_time) VALUES
(1,'农夫山泉','18.9L桶装',20.00,30.00,'天然矿泉水','2024-01-15','2024-01-15'),
(2,'怡宝','18.9L桶装',22.00,30.00,'纯净水','2024-01-15','2024-01-15'),
(3,'景田百岁山','18.9L桶装',25.00,50.00,'矿泉水','2024-01-15','2024-01-15'),
(4,'娃哈哈','18.9L桶装',15.00,30.00,'纯净水','2024-01-15','2024-01-15'),
(5,'恒大冰泉','18.9L桶装',28.00,50.00,'矿泉水','2024-01-15','2024-01-15'),
(6,'怡宝','4.5L瓶装',12.00,0.00,'小瓶纯净水','2024-01-15','2024-01-15'),
(7,'农夫山泉','4.5L瓶装',14.00,0.00,'小瓶矿泉水','2024-01-15','2024-01-15'),
(8,'娃哈哈','4.5L瓶装',8.00,0.00,'小瓶纯净水','2024-01-15','2024-01-15'),
(9,'农夫山泉','550ml*24瓶',36.00,0.00,'整箱小瓶水','2024-01-15','2024-01-15'),
(10,'怡宝','550ml*24瓶',32.00,0.00,'整箱小瓶水','2024-01-15','2024-01-15');

-- ===== 4. 员工（16人） =====
INSERT INTO staff (id,name,phone,password,factory_id,station_id,role,status,create_time,update_time) VALUES
-- 厂长
(1,'王建国','13905310001',NULL,1,NULL,'FACTORY_ADMIN',1,'2024-01-15','2024-01-15'),
(2,'李志强','13905310002',NULL,1,NULL,'FACTORY_ADMIN',1,'2024-03-01','2024-03-01'),
-- 洪家楼（站长+3配送）
(3,'张建国','13805310001',NULL,1,1,'STATION_MANAGER',1,'2024-01-20','2024-01-20'),
(4,'李永强','13705310001',NULL,1,1,'DELIVERY',1,'2024-01-22','2024-01-22'),
(5,'王海涛','13705310002',NULL,1,1,'DELIVERY',1,'2024-01-22','2024-01-22'),
(6,'赵德明','13705310003',NULL,1,1,'DELIVERY',1,'2024-03-01','2024-03-01'),
-- 唐冶（站长+3配送）
(7,'刘大明','13805310002',NULL,1,2,'STATION_MANAGER',1,'2024-02-01','2024-02-01'),
(8,'孙志强','13705310004',NULL,1,2,'DELIVERY',1,'2024-02-05','2024-02-05'),
(9,'周建民','13705310005',NULL,1,2,'DELIVERY',1,'2024-02-05','2024-02-05'),
(10,'吴春雷','13705310006',NULL,1,2,'DELIVERY',1,'2024-04-01','2024-04-01'),
-- 王舍人（站长+2配送）
(11,'孙丽华','13805310003',NULL,1,3,'STATION_MANAGER',1,'2024-02-15','2024-02-15'),
(12,'郑辉东','13705310007',NULL,1,3,'DELIVERY',1,'2024-02-18','2024-02-18'),
(13,'马文博','13705310008',NULL,1,3,'DELIVERY',1,'2024-02-18','2024-02-18'),
-- 郭店（已关闭）
(14,'马国庆','13805310004',NULL,1,4,'STATION_MANAGER',0,'2024-04-01','2025-06-15'),
(15,'陈卫东','13705310009',NULL,1,4,'DELIVERY',0,'2024-04-05','2025-06-15'),
-- admin + 离职
(16,'admin','00000000000',NULL,1,NULL,'FACTORY_ADMIN',1,'2024-01-15','2024-01-15'),
(17,'宋文涛','13705310010',NULL,1,1,'DELIVERY',0,'2024-02-01','2025-03-01');
