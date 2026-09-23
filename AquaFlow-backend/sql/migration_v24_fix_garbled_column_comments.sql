-- =============================================================================
-- V24: 归一化「导出期编码事故」破坏的列注释 / 表注释
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p<密码> aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v24_fix_garbled_column_comments.sql
--
-- -----------------------------------------------------------------------------
-- 背景
--   2026-09-11 从开发库导出 schema.sql 时，多处 COMMENT 以错误编码写回，
--   在【真实库】里同样落成了乱码（不是只在文件里坏）。实测受影响（不含 bak_* 备份表）：
--     orders.payment_method / address_snapshot_lat / address_snapshot_lng / delivery_station_id
--     payment_record.amount / water_amount / payment_method / ticket_water_type_id / ticket_qty / status
--     payment_record 表注释、customer_barrel_asset.quantity + 表注释
--     feedback.staff_id / category / content / contact + 表注释
--     file_info 全部 8 条注释 + 表注释（这批更严重，已退化成 '?????'，原文不可恢复）
--     ticket_record.ticket_source
--
--   危害不是"看着难受"：
--     1) schema.sql 是新建库的唯一基线，乱码会随每次导出继续扩散；
--     2) orders/payment_record 的 payment_method 注释里夹带**已废弃的映射**（4=挂账），
--        与 PayMethod.java（1微信 / 2现金 / 3水票）直接冲突，照注释写代码必错；
--     3) file_info 那批原文已彻底丢失，只能按 FileInfo.java 的字段语义重写。
--
-- -----------------------------------------------------------------------------
-- 影响面（为什么可以安全 MODIFY COLUMN）
--   · 只改 COMMENT 元数据：类型、长度、精度、默认值、可空性全部原样保留，
--     不触碰任何行数据（定义等价时 MODIFY COLUMN 是原地改元数据）。
--   · COMMENT 不参与 SQL 解析，无视图 / 触发器 / 存储过程 / 生成列 依赖它。
--   · 语义真值来源仍是代码：OrderStatus / PayMethod / PaymentStatus / FileInfo / PaymentRecord。
--
-- 幂等：可重复执行（重复执行只是把同样的注释再写一遍）。
-- 回滚（**乱码原文并未丢失，可直接取回**）：
--   · 本库存在同期的 `*_bak_20260910` 快照表，它们**刻意未被本脚本修改**，
--     乱码原文仍在其中。例如要还原 orders.payment_method：
--       SELECT column_comment FROM information_schema.columns
--        WHERE table_schema=DATABASE() AND table_name='orders_bak_20260910'
--          AND column_name='payment_method';
--     再按该值 MODIFY COLUMN 回去即可。
--   · 唯一无法回滚的是 file_info 那批 —— 它在本脚本执行**之前**就已经退化成 '?????'，
--     原文已永久丢失（正因如此才只能按 FileInfo.java 的字段语义重写）。
-- 注意：bak_* / *_bak_* 备份表**刻意不动** —— 它们是历史快照，不应被后续修复改写。
-- =============================================================================

SET @db := DATABASE();

-- 0) 预检：防在错误库上执行
SET @has_orders := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders');
SET @s := IF(@has_orders>0,
  "SELECT '开始归一化列注释（V24）' AS note",
  "SELECT 'ABORT: 当前库没有 orders 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) orders：乱码 4 列 + 停在旧口径的 status / payment_status
SET @s := IF(@has_orders>0,
  "ALTER TABLE orders
     MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '1'
       COMMENT '状态：1待配送 2配送中 3已送达 4已完成 5已取消（连续编号，历史 1/3/4/5/6 已废弃）',
     MODIFY COLUMN `payment_status` tinyint DEFAULT '1'
       COMMENT '支付状态：0未支付 1待收款 2已付款 3已退款 4已取消。注意列默认值是 1（待收款），与 PaymentStatus.UNPAID=0 不同，CAS 的 expected 必须按库实际值取',
     MODIFY COLUMN `payment_method` tinyint DEFAULT NULL
       COMMENT '支付方式: 1=微信 2=现金(货到付款) 3=水票（水票下单即视同已付）',
     MODIFY COLUMN `address_snapshot_lat` decimal(10,7) DEFAULT NULL COMMENT '地址快照纬度',
     MODIFY COLUMN `address_snapshot_lng` decimal(10,7) DEFAULT NULL COMMENT '地址快照经度',
     MODIFY COLUMN `delivery_station_id` bigint DEFAULT NULL
       COMMENT '实际履约水站（可被站长外派/抢单切换，为空=在抢单池）'",
  "SELECT 'skip: orders 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) payment_record：乱码 6 列 + 表注释
SET @s := IF(@has_orders>0,
  "ALTER TABLE payment_record
     MODIFY COLUMN `customer_id` bigint NOT NULL COMMENT '客户ID',
     MODIFY COLUMN `amount` decimal(10,2) NOT NULL COMMENT '支付金额（退款冲正流水为负值）',
     MODIFY COLUMN `water_amount` decimal(10,2) DEFAULT '0.00' COMMENT '水费金额',
     MODIFY COLUMN `barrel_deposit` decimal(10,2) DEFAULT '0.00' COMMENT '桶押金金额',
     MODIFY COLUMN `excess_barrels` int DEFAULT '0' COMMENT '超出桶数',
     MODIFY COLUMN `payment_method` tinyint NOT NULL
       COMMENT '支付方式: 1=微信 2=现金(货到付款) 3=水票（水票下单即视同已付）',
     MODIFY COLUMN `ticket_water_type_id` bigint DEFAULT NULL
       COMMENT '在线购票：所购商品ID（即原 water_type；非购票支付为空）',
     MODIFY COLUMN `ticket_qty` int DEFAULT NULL COMMENT '在线购票：购买张数',
     MODIFY COLUMN `status` tinyint DEFAULT '1' COMMENT '状态: 1=待支付 2=已支付 3=已退款 4=已取消',
     MODIFY COLUMN `note` varchar(200) DEFAULT NULL COMMENT '备注',
     COMMENT='支付记录'",
  "SELECT 'skip: payment_record 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) customer_barrel_asset：quantity 注释 + 表注释
SET @s := IF(@has_orders>0,
  "ALTER TABLE customer_barrel_asset
     MODIFY COLUMN `customer_id` int NOT NULL COMMENT '客户ID',
     MODIFY COLUMN `quantity` int NOT NULL DEFAULT '0'
       COMMENT '持有桶权益数（= Σ customer_barrel_lot.remain_qty）',
     COMMENT='客户桶权益汇总（按 customer×station×product；数量与可退金额均为派生值，真相源是 customer_barrel_lot）'",
  "SELECT 'skip: customer_barrel_asset 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) ticket_record：ticket_source 注释
SET @s := IF(@has_orders>0,
  "ALTER TABLE ticket_record
     MODIFY COLUMN `ticket_source` tinyint DEFAULT '1' COMMENT '票据来源: 1=线上 2=线下'",
  "SELECT 'skip: ticket_record 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5) feedback：乱码 4 列 + 表注释
SET @s := IF(@has_orders>0,
  "ALTER TABLE feedback
     MODIFY COLUMN `staff_id` int DEFAULT NULL COMMENT '提交人（配送员）ID',
     MODIFY COLUMN `category` varchar(50) DEFAULT NULL COMMENT '分类：bug/feature/other',
     MODIFY COLUMN `content` text NOT NULL COMMENT '反馈内容',
     MODIFY COLUMN `contact` varchar(100) DEFAULT NULL COMMENT '联系方式',
     COMMENT='意见反馈'",
  "SELECT 'skip: feedback 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 6) file_info：8 列注释原文已丢失（'?????'），按 FileInfo.java 字段语义重写 + 表注释
SET @s := IF(@has_orders>0,
  "ALTER TABLE file_info
     MODIFY COLUMN `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
     MODIFY COLUMN `file_size` bigint DEFAULT '0' COMMENT '文件大小（字节）',
     MODIFY COLUMN `file_type` varchar(50) DEFAULT '' COMMENT '文件类型（image/video/document/other）',
     MODIFY COLUMN `mime_type` varchar(100) DEFAULT '' COMMENT 'MIME 类型',
     MODIFY COLUMN `object_name` varchar(500) NOT NULL
       COMMENT '腾讯云 COS 对象键（如 public/product/abc.jpg）',
     MODIFY COLUMN `category` varchar(50) DEFAULT 'general'
       COMMENT '业务分类（general/banner/product/other）',
     MODIFY COLUMN `uploader_id` int DEFAULT NULL COMMENT '上传人员工ID',
     MODIFY COLUMN `uploader_name` varchar(50) DEFAULT '' COMMENT '上传人姓名',
     COMMENT='文件管理（COS 对象登记）'",
  "SELECT 'skip: file_info 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 7) 货到付款唯一控制点的注释（客户级授权，无站点总闸）
SET @has_cfg := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_station_config'
                   AND COLUMN_NAME='offline_payment_enabled');
SET @s := IF(@has_cfg>0,
  "ALTER TABLE customer_station_config
     MODIFY COLUMN `offline_payment_enabled` tinyint NOT NULL DEFAULT '0'
       COMMENT '该客户在该站是否允许线下支付（货到付款）。全系统唯一控制点，由站长在客户画像里逐个开通；无站点级总闸'",
  "SELECT 'skip: customer_station_config.offline_payment_enabled 不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 8) 校验 A：业务表里仍含乱码的列（应为空集；bak_* 备份表不在检查范围内）
SELECT '校验A：业务表残留乱码列（应为空集）' AS check_item;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db
   AND TABLE_NAME NOT LIKE 'bak\_%' AND TABLE_NAME NOT LIKE '%\_bak\_%' AND TABLE_NAME NOT LIKE '%\_bak'
   AND (COLUMN_COMMENT REGEXP '[鍦鏀寰鐜姘鎸鑸]' OR COLUMN_COMMENT LIKE '%?%');

-- 9) 校验 B：关键列注释（应全部为可读中文）
SELECT '校验B：orders / payment_record 关键列注释' AS check_item;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db
   AND ((TABLE_NAME='orders' AND COLUMN_NAME IN ('status','payment_status','payment_method',
                                                 'address_snapshot_lat','address_snapshot_lng',
                                                 'delivery_station_id','batch_id'))
     OR (TABLE_NAME='payment_record' AND COLUMN_NAME IN ('amount','payment_method','status')));

-- 10) 校验 C：station.offline_payment_enabled 应为空（v22 已删）
SELECT '校验C：station.offline_payment_enabled（应为空）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station' AND COLUMN_NAME='offline_payment_enabled';
