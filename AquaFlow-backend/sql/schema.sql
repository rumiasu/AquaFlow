-- ============================================================
-- AquaFlow 数据库结构基线（初始化用）
--
-- 生成时间：2026-09-11（2026-09-12 校正漂移，2026-09-15 清理废弃对象）
-- 来源：从开发环境实际数据库导出（37 张业务表，无视图）
--
-- 说明：
--   1. 本文件是当前库结构的唯一基线，已包含桶权益模型相关表
--      （customer_barrel_lot / customer_barrel_over / barrel_record_lot /
--        order_transfer 等），旧版本基线缺失这些表，请勿再使用。
--   2. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行不会覆盖或清空已有表。
--      ⚠️ 因此本文件**只能用于新建空库**：对已存在的表，改列/删列不会生效，
--      结构性变更必须另写幂等迁移脚本（见 sql/README.md）。
--   3. 不含备份表（bak_* / *_bak_*）与任何测试数据。
--   4. 水厂端已彻底移除：无 factory 表、无各表 factory_id 列、无 FACTORY_ADMIN 角色。
--      演进过程见 sql/README.md「历史迁移演进」。
--   5. [2026-09-12 已归档] 旧欠桶台账 customer_owed_barrel 已从基线中移除：
--      该表 0 行、全仓零读写，已由 migration_v25 备份并改名为 bak_v25_customer_owed_barrel_retired。
--      欠桶一律读 customer_barrel_over（按 customer×station×product，over>0 为欠桶、<0 为水站暂存）。
--   6. [2026-09-12 校正] 与真实库逐列比对后修掉三处漂移：
--      · station.offline_payment_enabled —— 真实库已 DROP（v22 已执行），本文件此前仍保留 → 已删。
--        货到付款的唯一控制点是 customer_station_config.offline_payment_enabled（站长按客户开通）。
--      · orders 的 payment_method / address_snapshot_lat / address_snapshot_lng /
--        delivery_station_id 四列注释是**导出时编码坏掉的乱码**（真实库里同样是乱码）→ 已按代码语义重写。
--      · orders.status / payment_status 注释停在旧口径（"3已完成"、无 4/5）→ 已补齐。
--   7. [2026-09-12 补索引] deposit_record 增加 idx_deposit_record_order(related_order_id, type)：
--      它是"按订单查押金流水"的唯一条件，而 applyDepositOnPaid（每次支付成功都调）与
--      refundOrder 都走它做幂等/释放判定，原来没有索引（migration_v26 已在真实库执行）。
--   8. [2026-09-15 清理] 移出两个非业务对象，本文件与真实库从此一致（均为 37 张表、0 视图）：
--      · 表 migration_diff_bucket_right —— 桶权益迁移期的一次性人工核对登记表，
--        由 migration_aq_bucket_right_v1_backfill.sql 建、全仓 0 处代码引用、迁移早已完成。
--      · 视图 v_station_exception_stats —— 近 30 天桶异常统计，同为 0 引用的人工查看产物。
--      两者此前只存在于基线与真实库、不参与运行，删除不影响任何读写路径。
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
  `adjustment_id` bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_client_token` (`client_token`),
  -- [AQ-ADJ] 一张调整单最多一条桶流水，作为「重复执行」的数据库级兜底
  UNIQUE KEY `uk_record_adjustment` (`adjustment_id`),
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
  `customer_id` int NOT NULL COMMENT '客户ID',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '持有桶权益数（= Σ customer_barrel_lot.remain_qty）',
  `right_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '可退桶款=Σ lot.remain_qty×unit_price',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `product_id` int NOT NULL DEFAULT '0',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset` (`customer_id`,`product_id`,`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户桶权益汇总（按 customer×station×product；数量与可退金额均为派生值，真相源是 customer_barrel_lot）';
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
CREATE TABLE IF NOT EXISTS `customer_station_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `offline_payment_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '该客户在该站是否允许线下支付（货到付款）。全系统唯一控制点，由站长在客户画像里逐个开通；无站点级总闸',
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
  `type` tinyint NOT NULL COMMENT '押金流水类型（见 DepositType）：1新增押金桶 2退押金 3丢桶赔偿 4人工调整 5预收押金 6退桶退押金 7异常补偿 8取消订单释放预收押金',
  `amount` decimal(10,2) NOT NULL COMMENT '金额（退还/释放为负值）',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '桶权益买入单价快照',
  `quantity` int DEFAULT NULL COMMENT '本次涉及桶数',
  `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID。既是"这笔押金属于哪张订单"的唯一凭据，也是入账/释放幂等的依据（按 related_order_id + type 去重），取消退款时必须落库',
  `note` varchar(200) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `adjustment_id` bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生',
  PRIMARY KEY (`id`),
  -- [AQ-ADJ] 一张调整单最多一条押金流水（重复执行的数据库级兜底）。
  -- 此前 deposit_record 没有任何唯一键，幂等完全依赖应用层。
  UNIQUE KEY `uk_deposit_adjustment` (`adjustment_id`),
  KEY `idx_deposit_record_station` (`station_id`),
  KEY `idx_deposit_record_order` (`related_order_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `feedback` (
  `id` int NOT NULL AUTO_INCREMENT,
  `staff_id` int DEFAULT NULL COMMENT '提交人（配送员）ID',
  `customer_id` int DEFAULT NULL COMMENT '客户ID（客户反馈时使用）',
  `category` varchar(50) DEFAULT NULL COMMENT '分类：bug/feature/other',
  `content` text NOT NULL COMMENT '反馈内容',
  `contact` varchar(100) DEFAULT NULL COMMENT '联系方式',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='意见反馈';
CREATE TABLE IF NOT EXISTS `file_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小（字节）',
  `file_type` varchar(50) DEFAULT '' COMMENT '文件类型（image/video/document/other）',
  `mime_type` varchar(100) DEFAULT '' COMMENT 'MIME 类型',
  `object_name` varchar(500) NOT NULL COMMENT '腾讯云 COS 对象键（如 public/product/abc.jpg）',
  `category` varchar(50) DEFAULT 'general' COMMENT '业务分类（general/banner/product/other）',
  `uploader_id` int DEFAULT NULL COMMENT '上传人员工ID',
  `uploader_name` varchar(50) DEFAULT '' COMMENT '上传人姓名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_file_type` (`file_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件管理（COS 对象登记）';
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
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1待配送 2配送中 3已送达 4已完成 5已取消（连续编号，历史 1/3/4/5/6 已废弃）',
  `payment_status` tinyint DEFAULT '1' COMMENT '支付状态：0未支付 1待收款 2已付款 3已退款 4已取消。注意列默认值是 1（待收款），与 PaymentStatus.UNPAID=0 不同，CAS 的 expected 必须按库实际值取',
  `payment_method` tinyint DEFAULT NULL COMMENT '支付方式: 1=微信 2=现金(货到付款) 3=水票（水票下单即视同已付）',
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
  `address_snapshot_lat` decimal(10,7) DEFAULT NULL COMMENT '地址快照纬度',
  `address_snapshot_lng` decimal(10,7) DEFAULT NULL COMMENT '地址快照经度',
  `station_id` bigint DEFAULT NULL COMMENT '订单归属水站（交易/营收归属，客户自选）',
  `delivery_station_id` bigint DEFAULT NULL COMMENT '实际履约水站（可被站长外派/抢单切换，为空=在抢单池）',
  `batch_id` bigint DEFAULT NULL COMMENT '所属批次。[已废弃] 全项目无 batch 表、无读写点，恒为 NULL，仅为兼容旧库列保留',
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
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint DEFAULT NULL,
  `amount` decimal(10,2) NOT NULL COMMENT '支付金额（退款冲正流水为负值）',
  `water_amount` decimal(10,2) DEFAULT '0.00' COMMENT '水费金额',
  `barrel_deposit` decimal(10,2) DEFAULT '0.00' COMMENT '桶押金金额',
  `excess_barrels` int DEFAULT '0' COMMENT '超出桶数',
  `payment_method` tinyint NOT NULL COMMENT '支付方式: 1=微信 2=现金(货到付款) 3=水票（水票下单即视同已付）',
  `ticket_water_type_id` bigint DEFAULT NULL COMMENT '在线购票：所购商品ID（即原 water_type；非购票支付为空）',
  `ticket_qty` int DEFAULT NULL COMMENT '在线购票：购买张数',
  `status` tinyint DEFAULT '1' COMMENT '状态: 1=待支付 2=已支付 3=已退款 4=已取消',
  `transaction_no` varchar(100) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_order_id` bigint GENERATED ALWAYS AS ((case when (`status` in (1,2)) then `order_id` else NULL end)) STORED COMMENT '仅当流水为活跃态(1待收款/2已付)时等于 order_id，否则 NULL；与 uk_payment_active_order 配合保证一单一条活跃流水',
  PRIMARY KEY (`id`),
  -- [DEF-3] 原为 UNIQUE KEY uk_payment_order_status(order_id,status)，
  -- 与「退款另立负金额冲正流水」的设计冲突：退款把原记录置 REFUNDED 后再插入一条
  -- REFUNDED 冲正流水，(order_id, 已退款) 必然重复 → 水票/现金已付订单永远取消不了。
  -- 改为普通索引（保留按订单+状态的查询性能），防重由应用层保证
  -- （PaymentServiceImpl.createPayment：已有 PAID/PENDING 流水即直接返回）。
  KEY `idx_payment_order_status` (`order_id`,`status`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_customer_id` (`customer_id`),
  -- [AQ-053] 数据库级防重：把「活跃态(status in 1,2) 的 order_id」落到 STORED 生成列再建唯一键。
  -- 既与退款冲正流水不冲突（退款后原记录与冲正流水均为 REFUNDED，生成列为 NULL，NULL 在唯一键中不参与比较），
  -- 又能在并发重复提交时由数据库兜底。无订单支付（水票直购）order_id 为 NULL，同样不受影响。
  UNIQUE KEY `uk_payment_active_order` (`active_order_id`),
  CONSTRAINT `fk_payment_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付记录';
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
  `ticket_source` tinyint DEFAULT '1' COMMENT '票据来源: 1=线上 2=线下',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `product_id` bigint NOT NULL DEFAULT '0',
  `station_id` bigint DEFAULT NULL,
  `adjustment_id` bigint DEFAULT NULL COMMENT '站长资产调整单ID（station_adjustment.id），NULL=非调整产生',
  PRIMARY KEY (`id`),
  -- [DEF-3] 原为 UNIQUE KEY uk_ticket_consume(order_id,product_id)，同一订单同一商品
  -- 只能有一条流水：取消水票已付订单时 refundTicket 要插入一条 source='退款' 的回补流水，
  -- 与已有的 source='消费' 消费流水撞唯一键（Duplicate entry 'N-M'），取消直接失败。
  -- 纳入 source 后，消费/退款各一条互不冲突；同时"消费"维度仍唯一，
  -- 仍能兜底并发双扣（consumeTicket 捕获 DuplicateKeyException 幂等跳过）。
  UNIQUE KEY `uk_ticket_consume` (`order_id`,`product_id`,`source`),
  -- [AQ-ADJ] 调整单幂等键。注意 uk_ticket_consume 对调整记录【零保护】：
  -- 调整场景 order_id 为 NULL，而 MySQL 唯一键中 NULL 互不冲突。
  UNIQUE KEY `uk_ticket_adjustment` (`adjustment_id`,`product_id`,`source`),
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
-- 站长资产调整单（人工补录 / 历史迁移 / 代客订正 的单据头）
-- 设计依据：站长资产调整单（单据头 + 反向单撤销 + 幂等键 + 纳入对账）。
--   注意：对应设计文档**不在本仓库内分发**，勿在其上写路径引用。
-- ============================================================
CREATE TABLE IF NOT EXISTS `station_adjustment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `adjust_no` varchar(32) NOT NULL COMMENT '单据号 ADJyyyymmdd-000001',
  `station_id` bigint NOT NULL COMMENT '发起站=资产所属站；跨站一律拒绝',
  `customer_id` bigint NOT NULL,
  `product_id` bigint DEFAULT NULL COMMENT '桶类调整必填',
  `adjust_type` varchar(32) NOT NULL COMMENT 'BARREL_GRANT/BARREL_REVOKE/OVER_ADJUST/DEPOSIT_GRANT/DEPOSIT_DEDUCT/TICKET_GRANT/TICKET_DEDUCT',
  `qty` int DEFAULT NULL COMMENT '桶/水票数量（绝对值，方向由 adjust_type 决定）',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '押金金额（正数，方向由 adjust_type 决定）',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '补录单价快照（桶权益用）',
  `price_source` tinyint DEFAULT NULL COMMENT '1订单实付 2当时商品押金 3当前商品押金(推断)',
  `is_migrated` tinyint NOT NULL DEFAULT '0' COMMENT '1=历史迁移（单价为推断，退款需二次确认）',
  `reason` varchar(200) NOT NULL COMMENT '调整原因（必填）',
  `evidence` varchar(500) DEFAULT NULL COMMENT '证据图 objectName，逗号分隔',
  `before_snapshot` varchar(500) DEFAULT NULL COMMENT '执行前快照 JSON',
  `after_snapshot` varchar(500) DEFAULT NULL COMMENT '执行后快照 JSON',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EFFECTIVE/REVERSED/REJECTED',
  `client_token` varchar(64) NOT NULL COMMENT '客户端幂等键',
  `operator_id` bigint NOT NULL COMMENT '发起人（站长）',
  `executor_id` bigint DEFAULT NULL COMMENT '执行人',
  `reverses` bigint DEFAULT NULL COMMENT '本单是反冲哪张单',
  `reversed_by` bigint DEFAULT NULL COMMENT '本单被哪张单反冲',
  `execute_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adjust_no` (`adjust_no`),
  UNIQUE KEY `uk_adjust_client_token` (`client_token`),
  KEY `idx_adjust_station_time` (`station_id`,`create_time`),
  KEY `idx_adjust_customer` (`customer_id`,`station_id`),
  KEY `idx_adjust_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站长资产调整单：人工补录/订正的唯一合法来源';

-- ============================================================
-- 对账结果落表（替代「只打日志」）
-- ============================================================
CREATE TABLE IF NOT EXISTS `reconciliation_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_date` date NOT NULL COMMENT '对账执行日',
  `check_key` varchar(64) NOT NULL COMMENT '检查项键（E3_rightVsLot / E5_physicalConservation 等）',
  `diff_count` int NOT NULL DEFAULT '0' COMMENT '不平条数',
  `level` varchar(8) NOT NULL DEFAULT 'ERROR' COMMENT 'ERROR/WARN',
  `sample_ids` varchar(500) DEFAULT NULL COMMENT '样本 id，供人工追查',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recon_run_check` (`run_date`,`check_key`),
  KEY `idx_recon_date` (`run_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账结果：每日每检查项一行';

SET FOREIGN_KEY_CHECKS = 1;
