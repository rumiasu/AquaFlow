-- ============================================================
-- AquaFlow 数据库结构基线（初始化用）
--
-- 生成时间：2026-09-11
-- 来源：从开发环境实际数据库导出（37 张业务表 + 1 个视图）
--
-- 说明：
--   1. 本文件是当前库结构的唯一基线，已包含桶权益模型相关表
--      （customer_barrel_lot / customer_barrel_over / barrel_record_lot /
--        order_transfer 等），旧版本基线缺失这些表，请勿再使用。
--   2. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行不会覆盖或清空已有表。
--   3. 不含备份表（bak_* / *_bak_*）与任何测试数据。
--   4. 水厂端已彻底移除：无 factory 表、无各表 factory_id 列、无 FACTORY_ADMIN 角色。
--      演进过程见 sql/README.md「历史迁移演进」。
--   5. customer_owed_barrel 为旧欠桶台账，已停止写入、待下线；
--      欠桶一律改读 customer_barrel_over。
--
-- 初始化：mysql -u root -p aquaflow < sql/schema.sql
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `address` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` int DEFAULT NULL COMMENT '关联客户ID',
  `name` varchar(50) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `province` varchar(50) DEFAULT NULL COMMENT '省',
  `city` varchar(50) DEFAULT NULL COMMENT '市',
  `district` varchar(50) DEFAULT NULL COMMENT '区/县',
  `is_default` tinyint DEFAULT '0',
  `label` varchar(50) DEFAULT NULL COMMENT '标签(家/公司/父母等)',
  `detail` varchar(255) NOT NULL COMMENT '详细地址',
  `lat` decimal(10,6) DEFAULT NULL COMMENT '纬度(地图解析后存)',
  `lng` decimal(10,6) DEFAULT NULL COMMENT '经度(地图解析后存)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地址表';
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int DEFAULT NULL,
  `username` varchar(50) DEFAULT NULL,
  `role` varchar(20) DEFAULT NULL,
  `module` varchar(50) NOT NULL,
  `action` varchar(50) NOT NULL,
  `target` varchar(100) DEFAULT NULL,
  `detail` text,
  `ip` varchar(50) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_module` (`module`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志表';
CREATE TABLE IF NOT EXISTS `barrel_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL,
  `station_id` bigint DEFAULT NULL,
  `type` int DEFAULT NULL,
  `related_order_id` bigint DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `status` int NOT NULL DEFAULT '1',
  `deposit_refund` decimal(10,2) DEFAULT '0.00',
  `note` varchar(500) DEFAULT NULL,
  `handle_note` varchar(500) DEFAULT NULL,
  `client_token` varchar(64) DEFAULT NULL COMMENT '客户端幂等token(纯还桶/退桶防重复提交)',
  `confirmed_by` bigint DEFAULT NULL COMMENT '确认收到空桶的操作人(DEF-7)',
  `confirmed_time` datetime DEFAULT NULL COMMENT '确认收到空桶时间',
  `over_before` int DEFAULT NULL COMMENT '变更前over(可负)',
  `over_after` int DEFAULT NULL COMMENT '变更后over(可负)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `handle_time` datetime DEFAULT NULL,
  `delivered_qty` int NOT NULL DEFAULT '0' COMMENT '本单送出满桶数(仅type=8配送收发明细)',
  `returned_qty` int NOT NULL DEFAULT '0' COMMENT '本单收回空桶数(仅type=8配送收发明细)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_client_token` (`client_token`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `barrel_record_lot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `record_id` bigint NOT NULL COMMENT 'barrel_record.id',
  `lot_id` bigint NOT NULL COMMENT 'customer_barrel_lot.id',
  `qty` int NOT NULL,
  `unit_price` decimal(10,2) NOT NULL COMMENT '核销时的批次单价快照',
  `amount` decimal(10,2) NOT NULL COMMENT 'qty × unit_price',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rl` (`record_id`,`lot_id`),
  KEY `idx_rl_lot` (`lot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退桶的权益批次核销明细; 纯还桶不写本表';
CREATE TABLE IF NOT EXISTS `company_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `company_name` varchar(200) DEFAULT NULL,
  `contact_person` varchar(100) DEFAULT NULL,
  `contact_phone` varchar(100) DEFAULT NULL,
  `payment_method` varchar(50) DEFAULT NULL,
  `due_days` int DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_company_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `customer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '客户名/公司名',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `last_delivery_time` datetime DEFAULT NULL COMMENT '最近配送时间',
  `customer_type` tinyint DEFAULT '1' COMMENT '1个人 2企业',
  `first_order_time` datetime DEFAULT NULL COMMENT '首次下单时间',
  `total_orders` int DEFAULT '0' COMMENT '累计订单数',
  `total_consumption` decimal(10,2) DEFAULT '0.00' COMMENT '累计消费',
  `avg_cycle_days` int DEFAULT NULL COMMENT '平均配送周期',
  `tags` varchar(200) DEFAULT NULL COMMENT '标签',
  `openid` varchar(100) DEFAULT NULL COMMENT 'wechat openid',
  `role` tinyint DEFAULT '2' COMMENT '角色: 1=站长 2=管理员',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户表';
CREATE TABLE IF NOT EXISTS `customer_barrel_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL COMMENT '瀹㈡埛ID',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '鎸佹湁妗惰祫浜ф暟',
  `right_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '可退桶款=Σ lot.remain_qty×unit_price',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `product_id` int NOT NULL DEFAULT '0',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset` (`customer_id`,`product_id`,`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='瀹㈡埛鎸佹湁妗惰祫浜э紙鎶奸噾妗讹紝涓??鎬у洖濉?巻鍙插悗鐢变笟鍔＄淮鎶わ級';
CREATE TABLE IF NOT EXISTS `customer_barrel_in_transit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `customer_id` bigint NOT NULL COMMENT 'Customer ID',
  `station_id` bigint NOT NULL COMMENT 'Station ID',
  `product_id` bigint NOT NULL COMMENT 'Product ID',
  `qty` int NOT NULL DEFAULT '0' COMMENT '配送中桶数 = 本单新购权益数(下单时算出的 shortage)',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '下单时桶权益单价快照(转为lot.unit_price)',
  `related_order_id` bigint DEFAULT NULL COMMENT 'Related order ID',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=配送中(已购待送) / DELIVERED=已送达(权益已转押金条, 记录保留可追溯) / CANCELLED=已取消',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transit_order_product` (`related_order_id`,`product_id`),
  KEY `idx_customer_station` (`customer_id`,`station_id`),
  KEY `idx_order` (`related_order_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户配送中桶: 已付款买下桶权益但尚未送达; 送达后转为押金条(lot)并计入权益。表名沿用历史命名 in_transit, 对外术语一律称「配送中」';
CREATE TABLE IF NOT EXISTS `customer_barrel_lot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `lot_no` varchar(32) NOT NULL COMMENT '押金条凭证号 DPyyyymmdd-000001',
  `customer_id` bigint NOT NULL,
  `station_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `unit_price` decimal(10,2) NOT NULL COMMENT '买入当时单价(快照): 2026年30元买的, 2027年退就退30元',
  `qty` int NOT NULL COMMENT '本批购买权益数',
  `remain_qty` int NOT NULL COMMENT '剩余未退权益数',
  `source_type` tinyint NOT NULL DEFAULT '1' COMMENT '1订单购买 2历史迁移 3人工补录',
  `price_source` tinyint NOT NULL DEFAULT '1' COMMENT '1订单实付 2当时商品押金 3当前商品押金(兜底推断)',
  `related_order_id` bigint DEFAULT NULL,
  `deposit_record_id` bigint DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 2已退完 3作废',
  `is_migrated` tinyint NOT NULL DEFAULT '0' COMMENT '1=历史迁移批次(单价为推断, 退款需二次确认)',
  `operator_id` bigint DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lot_no` (`lot_no`),
  KEY `idx_lot_csp` (`customer_id`,`station_id`,`product_id`,`status`),
  KEY `idx_lot_order` (`related_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='桶权益批次(押金条): 金额唯一真相源';
CREATE TABLE IF NOT EXISTS `customer_barrel_over` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `station_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `over_qty` int NOT NULL DEFAULT '0' COMMENT '过占=占用-权益; 正数=欠桶, 负数=多还桶(水站暂存), 均为合法状态',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_over` (`customer_id`,`station_id`,`product_id`),
  KEY `idx_over_station` (`station_id`),
  KEY `idx_over_cs` (`customer_id`,`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户过占桶(按商品, 可负, A水多还不能抵B水欠)';
CREATE TABLE IF NOT EXISTS `customer_deposit_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '押金余额',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_station` (`customer_id`,`station_id`),
  KEY `idx_deposit_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户按水站隔离的押金余额';
CREATE TABLE IF NOT EXISTS `customer_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `type` varchar(50) DEFAULT NULL,
  `title` varchar(200) DEFAULT NULL,
  `content` text,
  `related_order_id` bigint DEFAULT NULL,
  `is_read` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `customer_owed_barrel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL COMMENT '客户ID',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `owed_qty` int DEFAULT '0',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owed_station` (`customer_id`,`station_id`),
  KEY `idx_owed_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户欠桶台账（配送完成差额，后续回收补欠桶，不进持有）';
CREATE TABLE IF NOT EXISTS `customer_station_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `offline_payment_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '该客户在该站是否允许线下支付',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_station` (`customer_id`,`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户水站权限配置';
CREATE TABLE IF NOT EXISTS `deposit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `product_id` bigint DEFAULT NULL COMMENT '桶权益对应商品(按商品隔离)',
  `type` tinyint NOT NULL COMMENT '1充值 2退款 3赔偿扣除 4调整',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '桶权益买入单价快照',
  `quantity` int DEFAULT NULL COMMENT '本次涉及桶数',
  `related_order_id` bigint DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_deposit_record_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `feedback` (
  `id` int NOT NULL AUTO_INCREMENT,
  `staff_id` int DEFAULT NULL COMMENT '鎻愪氦浜洪厤閫佸憳ID',
  `customer_id` int DEFAULT NULL COMMENT '客户ID（客户反馈时使用）',
  `category` varchar(50) DEFAULT NULL COMMENT '鍒嗙被锛歜ug/feature/other',
  `content` text NOT NULL COMMENT '鍙嶉?鍐呭?',
  `contact` varchar(100) DEFAULT NULL COMMENT '鑱旂郴鏂瑰紡',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='鍙嶉?寤鸿?';
CREATE TABLE IF NOT EXISTS `file_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL COMMENT '?????',
  `file_size` bigint DEFAULT '0' COMMENT '????(??)',
  `file_type` varchar(50) DEFAULT '' COMMENT '????(image/video/document/other)',
  `mime_type` varchar(100) DEFAULT '' COMMENT 'MIME??',
  `object_name` varchar(500) NOT NULL COMMENT 'OSS???',
  `category` varchar(50) DEFAULT 'general' COMMENT '??(general/banner/product/other)',
  `uploader_id` int DEFAULT NULL COMMENT '???ID',
  `uploader_name` varchar(50) DEFAULT '' COMMENT '?????',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_file_type` (`file_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='?????';
CREATE TABLE IF NOT EXISTS `inventory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '库存数量',
  `enabled` int NOT NULL DEFAULT '1',
  `ticket_enabled` int NOT NULL DEFAULT '0',
  `ticket_price` decimal(10,2) NOT NULL DEFAULT '0.00',
  `priority_display` int NOT NULL DEFAULT '0' COMMENT '优先展示: 0 否 1 是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_station_product` (`station_id`,`product_id`),
  KEY `idx_inventory_product` (`product_id`),
  CONSTRAINT `fk_inventory_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存表';
CREATE TABLE IF NOT EXISTS `inventory_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `delta` int NOT NULL COMMENT '库存变动量：正=入库/回补，负=消耗/出库',
  `type` varchar(32) NOT NULL COMMENT 'INBOUND入库 / CONSUME下单扣减 / CANCEL_RESTORE取消回补 / REFUND_RESTORE退款回补 / ADJUST盘点调整',
  `ref_id` bigint DEFAULT NULL COMMENT '关联单据ID（订单ID等）',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人员工ID',
  `note` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inv_record_station_product` (`station_id`,`product_id`,`create_time`),
  KEY `idx_inv_record_ref` (`ref_id`),
  KEY `idx_inv_record_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水';
CREATE TABLE IF NOT EXISTS `migration_diff_bucket_right` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint DEFAULT NULL,
  `station_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  `kind` varchar(48) NOT NULL COMMENT 'LOT_VS_DEPOSIT | OVER_VS_OLD_OWED | RIGHT_AMT_EXCEED_BALANCE',
  `expected_val` decimal(12,2) DEFAULT NULL,
  `actual_val` decimal(12,2) DEFAULT NULL,
  `diff_val` decimal(12,2) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `handled` tinyint NOT NULL DEFAULT '0' COMMENT '0=待处理 1=已人工处理',
  `handled_note` varchar(500) DEFAULT NULL COMMENT '人工处理说明',
  PRIMARY KEY (`id`),
  KEY `idx_diff_kind` (`kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='桶权益迁移差异登记(人工核对用, 不强行对齐)';
CREATE TABLE IF NOT EXISTS `notice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint DEFAULT NULL,
  `publisher_id` bigint DEFAULT NULL,
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` text COMMENT '内容',
  `type` tinyint DEFAULT '1' COMMENT '类型: 1=系统公告 2=水站通知 3=促销活动',
  `status` tinyint DEFAULT '1' COMMENT '1=发布 0=下架',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告表';
CREATE TABLE IF NOT EXISTS `order_barrel_exception` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Exception ID',
  `order_id` bigint NOT NULL COMMENT 'Order ID',
  `customer_id` bigint NOT NULL COMMENT 'Customer ID',
  `station_id` bigint NOT NULL COMMENT 'Station ID',
  `delivery_staff_id` bigint DEFAULT NULL COMMENT 'Delivery Staff ID',
  `delivery_qty` int NOT NULL DEFAULT '0' COMMENT 'Expected delivery quantity',
  `return_qty` int NOT NULL DEFAULT '0' COMMENT 'Actual return quantity',
  `discrepancy` int NOT NULL DEFAULT '0' COMMENT 'Difference = delivery - return (positive=shortage, negative=overage)',
  `category` varchar(32) NOT NULL COMMENT 'Category: RETURN_SHORT/RETURN_OVER/RETURN_REFUSE/RETURN_DAMAGE/STATION_SHORTAGE/CUSTOMER_REFUSE/OTHER',
  `type` varchar(32) DEFAULT NULL COMMENT 'Specific type',
  `staff_action` varchar(16) DEFAULT 'FULL' COMMENT 'Field action: FULL/PARTIAL/REFUSE/OWE',
  `staff_note` varchar(500) DEFAULT NULL COMMENT 'Delivery staff note',
  `water_given` int DEFAULT '0' COMMENT 'Actual water given quantity',
  `water_owed` int DEFAULT '0' COMMENT 'Owed water quantity',
  `manager_action` varchar(16) DEFAULT 'IGNORE' COMMENT 'Mediation action: REFUND_TICKET/REFUND_CASH/WAIVE_DEPOSIT/ADJUST_ASSET/RESCHEDULE/IGNORE',
  `refund_ticket_qty` int DEFAULT '0' COMMENT 'Refund ticket quantity',
  `refund_cash_amount` decimal(10,2) DEFAULT NULL COMMENT 'Refund cash amount',
  `adjust_asset_qty` int DEFAULT '0' COMMENT 'Adjust barrel asset quantity (positive/negative)',
  `adjust_product_id` bigint DEFAULT NULL COMMENT 'Adjusted product ID',
  `manager_note` varchar(500) DEFAULT NULL COMMENT 'Station manager note',
  `suggested_ticket_qty` int DEFAULT '0' COMMENT 'Suggested refund ticket quantity',
  `suggested_cash_amount` decimal(10,2) DEFAULT NULL COMMENT 'Suggested refund cash amount',
  `status` varchar(16) NOT NULL DEFAULT 'STAFF_RECORDED' COMMENT 'Status: STAFF_RECORDED/MANAGER_PENDING/MANAGER_APPROVED/EXECUTING/EXECUTED/IGNORED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `decided_at` datetime DEFAULT NULL COMMENT 'Manager decision time',
  `executed_at` datetime DEFAULT NULL COMMENT 'Execution complete time',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_station_status` (`station_id`,`status`),
  KEY `idx_customer_station` (`customer_id`,`station_id`),
  KEY `idx_staff_created` (`delivery_staff_id`,`created_at`),
  KEY `idx_category_status` (`category`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Order barrel exception record table';
CREATE TABLE IF NOT EXISTS `order_image` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1正常送达 2异常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_image_order` (`order_id`),
  CONSTRAINT `fk_order_image_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单图片表';
CREATE TABLE IF NOT EXISTS `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `product_name_snapshot` varchar(100) NOT NULL COMMENT '商品名称快照',
  `brand_snapshot` varchar(100) DEFAULT NULL COMMENT '品牌快照',
  `spec_snapshot` varchar(100) DEFAULT NULL COMMENT '规格快照',
  `price` decimal(10,2) NOT NULL COMMENT '单价',
  `quantity` int NOT NULL COMMENT '数量',
  `deposit` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '押金',
  `subtotal` decimal(10,2) NOT NULL COMMENT '小计',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deducted_qty` int DEFAULT NULL COMMENT '下单时实际扣减的库存数量（库存不足时小于 quantity）',
  PRIMARY KEY (`id`),
  KEY `idx_order_item_order` (`order_id`),
  KEY `idx_order_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单商品明细';
CREATE TABLE IF NOT EXISTS `order_template` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `name` varchar(100) DEFAULT NULL COMMENT '模板名称(如:家里/公司)',
  `quantity` int DEFAULT '1',
  `address_id` int DEFAULT NULL,
  `special_note` varchar(500) DEFAULT NULL,
  `enabled` int NOT NULL DEFAULT '1',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认模板 0=否 1=是',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_customer_id` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `order_template_item` (
  `id` int NOT NULL AUTO_INCREMENT,
  `template_id` int NOT NULL COMMENT '模板ID',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '桶数',
  `product_id` int NOT NULL DEFAULT '0',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  PRIMARY KEY (`id`),
  KEY `idx_template_id` (`template_id`),
  KEY `idx_template_item_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `order_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `kind` varchar(20) NOT NULL COMMENT 'STAFF 配送员转单 / DIRECTED 站间指定外派退回',
  `sub_kind` varchar(30) DEFAULT NULL COMMENT 'RETURN_STATION/TRANSFER/REDISPATCH/DIRECTED_RETURN',
  `from_staff_id` bigint DEFAULT NULL COMMENT '发起方配送员ID',
  `to_staff_id` bigint DEFAULT NULL COMMENT '目标配送员ID',
  `from_station_id` bigint DEFAULT NULL COMMENT '发起方水站ID',
  `to_station_id` bigint DEFAULT NULL COMMENT '目标水站ID',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
  `reason` varchar(255) DEFAULT NULL COMMENT '原因/备注',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人员工ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ot_order` (`order_id`),
  KEY `idx_ot_order_status` (`order_id`,`status`),
  KEY `idx_ot_pending` (`status`,`kind`),
  KEY `idx_ot_from_staff` (`from_staff_id`),
  KEY `idx_ot_to_staff` (`to_staff_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单转单记录';
CREATE TABLE IF NOT EXISTS `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `address_id` bigint NOT NULL COMMENT '地址ID',
  `quantity` int NOT NULL COMMENT '数量',
  `source` tinyint NOT NULL COMMENT '来源：1电话 2微信 3小程序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1待配送 2配送中 3已完成',
  `payment_status` tinyint DEFAULT '1' COMMENT '1待付款 2已付款',
  `payment_method` tinyint DEFAULT NULL COMMENT '鏀?粯鏂瑰紡: 1=寰?俊 2=鐜伴噾 3=姘寸エ 4=鎸傝处',
  `settlement_status` tinyint DEFAULT '1' COMMENT '1未结算 2已结算',
  `due_date` date DEFAULT NULL COMMENT '应付款日期',
  `delivery_bucket_qty` int DEFAULT '0' COMMENT '送出空桶数',
  `return_bucket_qty` int DEFAULT '0' COMMENT '回收空桶数',
  `delivery_staff_id` bigint DEFAULT NULL COMMENT '配送员ID',
  `guard_info` varchar(200) DEFAULT NULL COMMENT '门卫信息',
  `delivery_time_request` varchar(100) DEFAULT NULL COMMENT '配送时间要求',
  `special_note` varchar(200) DEFAULT NULL COMMENT '特殊说明',
  `receiver_name` varchar(50) DEFAULT NULL COMMENT '收件人姓名快照',
  `receiver_phone` varchar(20) DEFAULT NULL COMMENT '收件人电话快照',
  `address_snapshot` varchar(500) DEFAULT NULL COMMENT '地址快照',
  `address_snapshot_lat` decimal(10,7) DEFAULT NULL COMMENT '鍦板潃蹇?収绾?害',
  `address_snapshot_lng` decimal(10,7) DEFAULT NULL COMMENT '鍦板潃蹇?収缁忓害',
  `station_id` bigint DEFAULT NULL COMMENT '订单归属水站',
  `delivery_station_id` bigint DEFAULT NULL COMMENT '鏈??灞ョ害閰嶉?绔?缁勬壒/鎵ｅ簱瀛?閰嶉?鐢?',
  `batch_id` bigint DEFAULT NULL COMMENT '所属批次',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `barrel_discrepancy` int DEFAULT '0' COMMENT '空桶差异',
  `barrel_discrepancy_note` varchar(200) DEFAULT NULL COMMENT '空桶差异说明',
  `exception_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Has exception',
  `exception_category` varchar(32) DEFAULT NULL COMMENT 'First exception category',
  `exception_count` int NOT NULL DEFAULT '0' COMMENT 'Exception count',
  `barrel_exception_id` bigint DEFAULT NULL COMMENT 'Related barrel exception record ID',
  `in_transit_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Has in-transit barrels',
  `product_id` bigint NOT NULL DEFAULT '0',
  `total_amount` decimal(10,2) DEFAULT '0.00' COMMENT '订单总金额',
  `water_amount` decimal(10,2) DEFAULT '0.00' COMMENT '水费金额',
  `deposit_amount` decimal(10,2) DEFAULT '0.00' COMMENT '押金金额',
  `idempotency_key` varchar(64) DEFAULT NULL COMMENT '幂等键',
  `first_barrel_order` tinyint(1) DEFAULT '0' COMMENT '是否首次桶装水订单(押金桶无需回桶)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_orders_idempotency_key` (`idempotency_key`),
  KEY `idx_orders_customer` (`customer_id`),
  KEY `idx_orders_address` (`address_id`),
  KEY `idx_orders_status_time` (`status`,`create_time`),
  KEY `idx_orders_address_status_time` (`address_id`,`status`,`create_time`),
  KEY `idx_orders_station` (`station_id`),
  KEY `idx_orders_delivery_station` (`delivery_station_id`),
  CONSTRAINT `fk_orders_address` FOREIGN KEY (`address_id`) REFERENCES `address` (`id`),
  CONSTRAINT `fk_orders_customer` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';
CREATE TABLE IF NOT EXISTS `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL COMMENT '订单ID（水票直购等无订单支付时为空）',
  `customer_id` bigint NOT NULL COMMENT '瀹㈡埛ID',
  `station_id` bigint DEFAULT NULL,
  `amount` decimal(10,2) NOT NULL COMMENT '鏀?粯閲戦?',
  `water_amount` decimal(10,2) DEFAULT '0.00' COMMENT '姘磋垂閲戦?',
  `barrel_deposit` decimal(10,2) DEFAULT '0.00' COMMENT '妗舵娂閲戦噾棰',
  `excess_barrels` int DEFAULT '0' COMMENT '瓒呭嚭妗舵暟',
  `payment_method` tinyint NOT NULL COMMENT '鏀?粯鏂瑰紡: 1=寰?俊 2=鐜伴噾 3=姘寸エ 4=鎸傝处',
  `ticket_water_type_id` bigint DEFAULT NULL COMMENT '姘寸エ鏀?粯鏃跺叧鑱旂殑姘寸被鍨婭D',
  `ticket_qty` int DEFAULT NULL COMMENT '姘寸エ鏀?粯寮犳暟',
  `status` tinyint DEFAULT '1' COMMENT '鐘舵?: 1=寰呮敮浠?2=宸叉敮浠?3=宸查?娆?4=宸插彇娑',
  `transaction_no` varchar(100) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL COMMENT '澶囨敞',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- [DEF-3] 原为 UNIQUE KEY uk_payment_order_status(order_id,status)，
  -- 与「退款另立负金额冲正流水」的设计冲突：退款把原记录置 REFUNDED 后再插入一条
  -- REFUNDED 冲正流水，(order_id, 已退款) 必然重复 → 水票/现金已付订单永远取消不了。
  -- 改为普通索引（保留按订单+状态的查询性能），防重由应用层保证
  -- （PaymentServiceImpl.createPayment：已有 PAID/PENDING 流水即直接返回）。
  KEY `idx_payment_order_status` (`order_id`,`status`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_customer_id` (`customer_id`),
  CONSTRAINT `fk_payment_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='鏀?粯璁板綍琛';
CREATE TABLE IF NOT EXISTS `product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '商品名称',
  `category` tinyint NOT NULL COMMENT '1 桶装水 2 瓶装水 3 饮水器',
  `brand` varchar(100) DEFAULT NULL COMMENT '品牌',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `image_object_name` varchar(500) DEFAULT NULL COMMENT '图片 COS 对象键',
  `description` text COMMENT '商品描述',
  `price` decimal(10,2) NOT NULL COMMENT '基础售价',
  `deposit` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '押金(只有桶装水使用)',
  `max_per_order` int DEFAULT NULL COMMENT '单次购买上限',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0 下架 1 正常 2 停售',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `ticket_enabled` int DEFAULT '0' COMMENT '是否支持水票支付',
  `ticket_price` decimal(10,2) DEFAULT '0.00' COMMENT '水票价格',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';
CREATE TABLE IF NOT EXISTS `staff` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `openid` varchar(100) DEFAULT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `role` varchar(30) NOT NULL DEFAULT 'DELIVERY' COMMENT '角色：STATION_MANAGER/DELIVERY',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站 (NULL=未绑定水站)',
  `status` tinyint DEFAULT '1' COMMENT '1在职 2离职',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `staff_station_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL COMMENT '申请人 staff.id (必须 DELIVERY)',
  `station_id` bigint NOT NULL COMMENT '申请绑定/解绑的水站',
  `type` tinyint NOT NULL COMMENT '1=绑定申请, 2=解绑申请',
  `status` tinyint NOT NULL COMMENT '1=待审批, 2=已同意, 3=已拒绝, 4=已取消',
  `apply_note` varchar(200) DEFAULT NULL COMMENT '申请说明',
  `handle_staff_id` bigint DEFAULT NULL COMMENT '审批人 (水站 STATION_MANAGER)',
  `handle_note` varchar(200) DEFAULT NULL COMMENT '审批说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `handle_time` datetime DEFAULT NULL COMMENT '审批时间',
  PRIMARY KEY (`id`),
  KEY `idx_app_staff` (`staff_id`),
  KEY `idx_app_station_status` (`station_id`,`status`),
  KEY `idx_app_type_status` (`type`,`status`),
  KEY `fk_app_handle_staff` (`handle_staff_id`),
  CONSTRAINT `fk_app_handle_staff` FOREIGN KEY (`handle_staff_id`) REFERENCES `staff` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_app_staff` FOREIGN KEY (`staff_id`) REFERENCES `staff` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_app_station` FOREIGN KEY (`station_id`) REFERENCES `station` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送员-水站绑定/解绑申请审批';
CREATE TABLE IF NOT EXISTS `station` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `address` varchar(200) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1营业 2停业',
  `offline_payment_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否允许线下支付总开关',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `station_exception_config` (
  `station_id` bigint NOT NULL COMMENT 'Station ID',
  `compensation_priority` json DEFAULT NULL COMMENT 'Compensation priority: ["REFUND_TICKET","REFUND_CASH","WAIVE_DEPOSIT"]',
  `auto_suggest_rules` json DEFAULT NULL COMMENT 'Auto suggest rules',
  `notify_templates` json DEFAULT NULL COMMENT 'Notification templates',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Station exception config table';
CREATE TABLE IF NOT EXISTS `ticket_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `remain_quantity` int NOT NULL DEFAULT '0' COMMENT '剩余水票数',
  `product_id` bigint NOT NULL DEFAULT '0',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_product_station` (`customer_id`,`product_id`,`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `ticket_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `increase_qty` int DEFAULT '0' COMMENT '增加数量',
  `decrease_qty` int DEFAULT '0' COMMENT '消费数量',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单',
  `source` varchar(50) DEFAULT NULL COMMENT '来源：购买/赠送/消费',
  `ticket_source` tinyint DEFAULT '1' COMMENT '鏉ユ簮: 1=绾夸笂 2=绾夸笅',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `product_id` bigint NOT NULL DEFAULT '0',
  `station_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  -- [DEF-3] 原为 UNIQUE KEY uk_ticket_consume(order_id,product_id)，同一订单同一商品
  -- 只能有一条流水：取消水票已付订单时 refundTicket 要插入一条 source='退款' 的回补流水，
  -- 与已有的 source='消费' 消费流水撞唯一键（Duplicate entry 'N-M'），取消直接失败。
  -- 纳入 source 后，消费/退款各一条互不冲突；同时"消费"维度仍唯一，
  -- 仍能兜底并发双扣（consumeTicket 捕获 DuplicateKeyException 幂等跳过）。
  UNIQUE KEY `uk_ticket_consume` (`order_id`,`product_id`,`source`),
  KEY `idx_ticket_record_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `user_token` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `user_type` varchar(20) NOT NULL COMMENT '用户类型: staff / customer',
  `refresh_token` varchar(500) NOT NULL COMMENT 'refresh_token字符串',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `device_info` varchar(100) DEFAULT NULL COMMENT '设备标识',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`,`user_type`),
  KEY `idx_refresh_token` (`refresh_token`),
  KEY `idx_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Token表';

-- ============================================================
-- 视图：水站桶异常统计（近 30 天）
-- ============================================================
CREATE OR REPLACE VIEW `v_station_exception_stats` AS
select `obe`.`station_id` AS `station_id`,`s`.`name` AS `station_name`,count(0) AS `total_exceptions`,sum((case when (`obe`.`category` = 'RETURN_SHORT') then 1 else 0 end)) AS `short_return_count`,sum((case when (`obe`.`category` = 'RETURN_OVER') then 1 else 0 end)) AS `over_return_count`,sum((case when (`obe`.`category` = 'STATION_SHORTAGE') then 1 else 0 end)) AS `shortage_count`,sum((case when (`obe`.`status` = 'STAFF_RECORDED') then 1 else 0 end)) AS `pending_count`,sum((case when (`obe`.`status` = 'EXECUTED') then 1 else 0 end)) AS `resolved_count`,coalesce(sum(`obe`.`refund_ticket_qty`),0) AS `total_refund_tickets`,coalesce(sum(`obe`.`refund_cash_amount`),0) AS `total_refund_cash`,avg(timestampdiff(HOUR,`obe`.`created_at`,`obe`.`decided_at`)) AS `avg_handle_hours` from (`order_barrel_exception` `obe` left join `station` `s` on((`obe`.`station_id` = `s`.`id`))) where (`obe`.`created_at` >= (now() - interval 30 day)) group by `obe`.`station_id`,`s`.`name`;

SET FOREIGN_KEY_CHECKS = 1;
