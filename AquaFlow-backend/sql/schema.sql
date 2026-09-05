-- ============================================================
-- AquaFlow 数据库结构基线 (schema.sql)
-- 从当前实际数据库 SHOW CREATE TABLE 导出
-- 生成时间: 2026-08-21
-- 用途: 全新空数据库初始化
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. factory (水厂)
-- ============================================================
CREATE TABLE `factory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `contact_person` varchar(50) DEFAULT NULL,
  `contact_phone` varchar(30) DEFAULT NULL,
  `address` varchar(200) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1营业 2停业',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 2. station (水站)
-- ============================================================
CREATE TABLE `station` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `factory_id` bigint DEFAULT NULL COMMENT '所属水厂',
  `name` varchar(100) NOT NULL,
  `manager` varchar(50) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `address` varchar(200) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1营业 2停业',
  `creator_staff_id` bigint DEFAULT NULL COMMENT '创建者站长ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 3. water_type (水类型)
-- ============================================================
CREATE TABLE `water_type` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '水类型名称',
  `brand` varchar(100) DEFAULT '' COMMENT '品牌',
  `spec` varchar(100) DEFAULT NULL COMMENT '容量/规格',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `image_url` varchar(500) DEFAULT '' COMMENT '图片URL',
  `description` text COMMENT '商品描述',
  `sort` int DEFAULT '0' COMMENT '排序',
  `status` tinyint DEFAULT '1' COMMENT '状态 0=下架 1=上架 2=停售',
  `price` decimal(10,2) DEFAULT '0.00',
  `deposit` decimal(10,2) DEFAULT '0.00' COMMENT '押金',
  `max_per_order` int NOT NULL DEFAULT '4' COMMENT '单次正常购买上限(桶)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_water_type_name_spec` (`name`,`spec`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水类型表';

-- ============================================================
-- 4. customer (客户)
-- ============================================================
CREATE TABLE `customer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '客户名/公司名',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `owner_station_id` bigint DEFAULT NULL COMMENT '归属水站ID',
  `station_id` bigint DEFAULT NULL COMMENT '当前水站ID',
  `owner_staff_id` bigint DEFAULT NULL COMMENT '绑定/负责业务员',
  `bind_time` datetime DEFAULT NULL COMMENT '绑站时间',
  `bind_reason` varchar(100) DEFAULT NULL COMMENT '绑站原因',
  `last_delivery_time` datetime DEFAULT NULL COMMENT '最近配送时间',
  `deposit_balance` decimal(10,2) DEFAULT '0.00' COMMENT '押金余额',
  `customer_type` tinyint DEFAULT '1' COMMENT '1个人 2企业',
  `first_order_time` datetime DEFAULT NULL COMMENT '首次下单时间',
  `total_orders` int DEFAULT '0' COMMENT '累计订单数',
  `total_consumption` decimal(10,2) DEFAULT '0.00' COMMENT '累计消费',
  `avg_cycle_days` int DEFAULT NULL COMMENT '平均订水周期',
  `tags` varchar(200) DEFAULT NULL COMMENT '标签',
  `openid` varchar(100) DEFAULT NULL COMMENT 'wechat openid',
  `current_station_id` bigint DEFAULT NULL COMMENT '当前选择的服务水站（用户端切换，不影响归属）',
  `role` tinyint DEFAULT '2' COMMENT '角色: 1=站长 2=客户',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_phone` (`phone`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户表';

-- ============================================================
-- 5. address (地址)
-- ============================================================
CREATE TABLE `address` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` int DEFAULT NULL COMMENT '所属客户ID',
  `name` varchar(50) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `is_default` tinyint DEFAULT '0',
  `label` varchar(50) DEFAULT NULL COMMENT '标签(家/公司/父母家)',
  `detail` varchar(255) NOT NULL COMMENT '详细地址',
  `lat` decimal(10,6) DEFAULT NULL COMMENT '纬度(地图导航用)',
  `lng` decimal(10,6) DEFAULT NULL COMMENT '经度(地图导航用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地址表';

-- ============================================================
-- 6. staff (员工)
-- ============================================================
CREATE TABLE `staff` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `password` varchar(100) DEFAULT NULL COMMENT '登录密码(启动时自动初始化)',
  `role` varchar(30) NOT NULL DEFAULT 'DELIVERY' COMMENT '角色:FACTORY_ADMIN/STATION_MANAGER/DELIVERY',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站',
  `factory_id` bigint DEFAULT NULL COMMENT '所属水厂',
  `status` tinyint DEFAULT '1' COMMENT '1在职 2离职',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 7. barrel_record (退桶记录)
-- ============================================================
CREATE TABLE `barrel_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `product_id` bigint DEFAULT NULL COMMENT '商品ID(桶装水)',
  `type` tinyint DEFAULT NULL COMMENT '1新增押金桶 2退桶 3丢失 4损坏 5赔偿 6人工调整',
  `quantity` int NOT NULL,
  `status` int NOT NULL DEFAULT '1',
  `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `deposit_refund` decimal(10,2) DEFAULT '0.00',
  `note` varchar(500) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID',
  `handle_note` varchar(500) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `handle_time` datetime DEFAULT NULL,
  `water_type_id` int DEFAULT NULL COMMENT '水类型ID（退桶资产按水类型归属）',
  PRIMARY KEY (`id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_status` (`status`),
  KEY `idx_barrel_record_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 8. batch (配送批次)
-- ============================================================
CREATE TABLE `batch` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1=待组批 2=配送中 3=已完成',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `delivery_person_id` int DEFAULT NULL COMMENT '配送员ID（关联staff）',
  `total_qty` int NOT NULL DEFAULT '0' COMMENT '总数量(自动累加)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_batch_status_time` (`status`,`create_time`),
  KEY `idx_batch_delivery_person` (`delivery_person_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送批次';

-- ============================================================
-- 9. batch_order (批次-订单关联)
-- ============================================================
CREATE TABLE `batch_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id` bigint NOT NULL COMMENT '批次ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_order` (`batch_id`,`order_id`),
  KEY `idx_batch_order_order` (`order_id`),
  CONSTRAINT `fk_batch_order_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
  CONSTRAINT `fk_batch_order_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次-订单关联表';

-- ============================================================
-- 10. orders (订单) - 注意: 需要先创建被引用的表
-- ============================================================
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `address_id` bigint NOT NULL COMMENT '地址ID',
  `water_type_id` bigint NOT NULL COMMENT '水类型ID',
  `quantity` int NOT NULL COMMENT '数量',
  `source` tinyint NOT NULL COMMENT '来源：1电话 2微信 3小程序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1=待组批 2=配送中 3=已完成',
  `payment_status` tinyint DEFAULT '1' COMMENT '1=待付款 2=已付款',
  `payment_method` tinyint DEFAULT NULL COMMENT '付款方式: 1=微信 2=现金 3=水票 4=挂账',
  `settlement_status` tinyint DEFAULT '1' COMMENT '1=未结算 2=已结算',
  `due_date` date DEFAULT NULL COMMENT '应付款日期',
  `delivery_bucket_qty` int DEFAULT '0' COMMENT '配送桶数量',
  `return_bucket_qty` int DEFAULT '0' COMMENT '回收桶数量',
  `delivery_staff_id` bigint DEFAULT NULL COMMENT '配送员ID',
  `guard_info` varchar(200) DEFAULT NULL COMMENT '门卫信息',
  `delivery_time_request` varchar(100) DEFAULT NULL COMMENT '配送时间要求',
  `special_note` varchar(200) DEFAULT NULL COMMENT '特殊说明',
  `receiver_name` varchar(50) DEFAULT NULL COMMENT '收件人姓名（可为空）',
  `receiver_phone` varchar(20) DEFAULT NULL COMMENT '收件人电话（可为空）',
  `address_snapshot` varchar(500) DEFAULT NULL COMMENT '地址快照',
  `address_snapshot_lat` decimal(10,7) DEFAULT NULL COMMENT '地址快照纬度',
  `address_snapshot_lng` decimal(10,7) DEFAULT NULL COMMENT '地址快照经度',
  `factory_id` bigint DEFAULT NULL COMMENT '水厂ID',
  `station_id` bigint DEFAULT NULL COMMENT '水站ID',
  `owner_station_id` bigint DEFAULT NULL COMMENT '归属站（客户归属站，配送/领取时保持原站）',
  `delivery_station_id` bigint DEFAULT NULL COMMENT '履约配送站（组批/扣库的配送站）',
  `batch_id` bigint DEFAULT NULL COMMENT '所属批次',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `barrel_discrepancy` int DEFAULT '0' COMMENT '差桶数量',
  `barrel_discrepancy_note` varchar(200) DEFAULT NULL COMMENT '差桶差异说明',
  PRIMARY KEY (`id`),
  KEY `idx_orders_customer` (`customer_id`),
  KEY `idx_orders_address` (`address_id`),
  KEY `idx_orders_water_type` (`water_type_id`),
  KEY `idx_orders_status_time` (`status`,`create_time`),
  KEY `idx_orders_address_status_time` (`address_id`,`status`,`create_time`),
  CONSTRAINT `fk_orders_address` FOREIGN KEY (`address_id`) REFERENCES `address` (`id`),
  CONSTRAINT `fk_orders_customer` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `fk_orders_water_type` FOREIGN KEY (`water_type_id`) REFERENCES `water_type` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';

-- ============================================================
-- 11. company_info (企业资料)
-- ============================================================
CREATE TABLE `company_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `company_name` varchar(100) NOT NULL,
  `contact_person` varchar(50) DEFAULT NULL,
  `contact_phone` varchar(30) DEFAULT NULL,
  `payment_method` varchar(50) DEFAULT NULL COMMENT '付款方式',
  `due_days` int DEFAULT '30' COMMENT '账期天数',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `customer_id` (`customer_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 13. customer_barrel_asset (客户持有桶资产)
-- ============================================================
CREATE TABLE `customer_barrel_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL COMMENT '客户ID',
  `water_type_id` int NOT NULL COMMENT '水类型ID',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '持有桶资产数',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_station` (`customer_id`,`water_type_id`,`station_id`),
  KEY `idx_barrel_asset_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户持有桶资产（押金桶，配送性回收历史后由业务维护）';

-- ============================================================
-- 14. customer_owed_barrel (客户欠桶台账)
-- ============================================================
CREATE TABLE `customer_owed_barrel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL COMMENT '客户ID',
  `water_type_id` int NOT NULL COMMENT '水类型ID',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '尚欠桶数',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owed_station` (`customer_id`,`water_type_id`,`station_id`),
  KEY `idx_owed_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户欠桶台账（配送完成差额，后续回收补欠桶，不进持有）';

-- ============================================================
-- 14b. customer_deposit_account (客户按水站押金余额)
-- ============================================================
CREATE TABLE `customer_deposit_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '押金余额',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_station` (`customer_id`,`station_id`),
  KEY `idx_deposit_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户按水站隔离的押金余额';

-- ============================================================
-- 15. customer_station_record (客户换站记录)
-- ============================================================
CREATE TABLE `customer_station_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `from_station_id` bigint DEFAULT NULL COMMENT '原水站',
  `to_station_id` bigint DEFAULT NULL COMMENT '新水站',
  `reason` varchar(200) DEFAULT NULL COMMENT '换站原因',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 16. deposit_record (押金流水)
-- ============================================================
CREATE TABLE `deposit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `type` tinyint NOT NULL COMMENT '1=充值 2=退款 3=赔偿售出 4=其他',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  `note` varchar(200) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_deposit_record_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 17. feedback (反馈建议)
-- ============================================================
CREATE TABLE `feedback` (
  `id` int NOT NULL AUTO_INCREMENT,
  `staff_id` int DEFAULT NULL COMMENT '提交人配送员ID',
  `customer_id` int DEFAULT NULL COMMENT '客户ID（客户反馈时使用）',
  `category` varchar(50) DEFAULT NULL COMMENT '分类：bug/feature/other',
  `content` text NOT NULL COMMENT '反馈内容',
  `contact` varchar(100) DEFAULT NULL COMMENT '联系方式',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈建议';

-- ============================================================
-- 18. file_info (文件管理)
-- ============================================================
CREATE TABLE `file_info` (
  `id` int NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小(字节)',
  `file_type` varchar(50) DEFAULT '' COMMENT '文件类型(image/video/document/other)',
  `mime_type` varchar(100) DEFAULT '' COMMENT 'MIME类型',
  `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键',
  `category` varchar(50) DEFAULT 'general' COMMENT '分类(general/banner/product/other)',
  `uploader_id` int DEFAULT NULL COMMENT '上传者ID',
  `uploader_name` varchar(50) DEFAULT '' COMMENT '上传者姓名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_file_type` (`file_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件管理';

-- ============================================================
-- 19. inventory (库存)
-- ============================================================
CREATE TABLE `inventory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `quantity` int NOT NULL DEFAULT 0 COMMENT '库存数量',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '0 不在商城销售 1 在商城销售',
  `sale_price` decimal(10,2) DEFAULT NULL COMMENT '销售价格(为空时使用product.price)',
  `ticket_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '0 不支持水票 1 支持水票',
  `ticket_price` decimal(10,2) DEFAULT NULL COMMENT '水票价格',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_station_product` (`station_id`,`product_id`),
  KEY `idx_inventory_product` (`product_id`),
  CONSTRAINT `fk_inventory_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水站商品库存';

-- ============================================================
-- 20. notice (公告)
-- 来源: 历史SQL中无CREATE TABLE，从当前实际数据库导出
-- ============================================================
CREATE TABLE `notice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` text COMMENT '内容',
  `type` tinyint DEFAULT '1' COMMENT '类型: 1=系统公告 2=水站通知 3=促销活动',
  `publisher_role` varchar(30) DEFAULT 'STATION_MANAGER' COMMENT '发布者角色',
  `status` tinyint DEFAULT '1' COMMENT '1=发布 0=下架',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告表';

-- ============================================================
-- 21. order_image (订单图片)
-- 来源: 历史SQL中无CREATE TABLE，从当前实际数据库导出
-- ============================================================
CREATE TABLE `order_image` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1=正常配送 2=异常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_image_order` (`order_id`),
  CONSTRAINT `fk_order_image_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单图片表';

-- ============================================================
-- 22. order_template (订单模板)
-- ============================================================
CREATE TABLE `order_template` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int NOT NULL,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `name` varchar(100) DEFAULT NULL COMMENT '模板名称(如:家里/公司)',
  `water_type_id` int DEFAULT NULL,
  `quantity` int DEFAULT '1',
  `address_id` int DEFAULT NULL,
  `special_note` varchar(500) DEFAULT NULL,
  `enabled` int NOT NULL DEFAULT '1',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认模板 0=否 1=是',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_template_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 23. order_template_item (订单模板明细)
-- ============================================================
CREATE TABLE `order_template_item` (
  `id` int NOT NULL AUTO_INCREMENT,
  `template_id` int NOT NULL COMMENT '模板ID',
  `water_type_id` int NOT NULL COMMENT '水类型ID',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '桶数',
  PRIMARY KEY (`id`),
  KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 24. payment_record (支付记录)
-- ============================================================
CREATE TABLE `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL COMMENT '订单ID（水票直接抵扣无额外支付时为空）',
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID(来自订单)',
  `amount` decimal(10,2) NOT NULL COMMENT '支付金额',
  `water_amount` decimal(10,2) DEFAULT '0.00' COMMENT '水费金额',
  `barrel_deposit` decimal(10,2) DEFAULT '0.00' COMMENT '桶押金金额',
  `excess_barrels` int DEFAULT '0' COMMENT '超出桶数',
  `payment_method` tinyint NOT NULL COMMENT '支付方式: 1=微信 2=现金 3=水票 4=挂账',
  `ticket_water_type_id` bigint DEFAULT NULL COMMENT '水票抵扣时关联的水类型ID',
  `ticket_qty` int DEFAULT NULL COMMENT '水票抵扣张数',
  `status` tinyint DEFAULT '1' COMMENT '状态: 1=待支付 2=已支付 3=已退款 4=已取消',
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_payment_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付记录表';

-- ============================================================
-- 25. risk_alert (风险预警)
-- ============================================================
CREATE TABLE `risk_alert` (
  `id` int NOT NULL AUTO_INCREMENT,
  `station_id` int NOT NULL,
  `alert_type` varchar(50) NOT NULL,
  `alert_level` tinyint DEFAULT '1',
  `title` varchar(100) NOT NULL,
  `content` text,
  `suggestion` text,
  `status` tinyint DEFAULT '1',
  `handle_note` varchar(200) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_station_id` (`station_id`),
  KEY `idx_alert_type` (`alert_type`),
  KEY `idx_alert_level` (`alert_level`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 26. station_payment_config (站点支付配置)
-- ============================================================
CREATE TABLE `station_payment_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint DEFAULT NULL COMMENT '水站ID, NULL=全局默认',
  `enable_wechat` tinyint DEFAULT '1' COMMENT '启用微信支付',
  `enable_cash` tinyint DEFAULT '1' COMMENT '启用现金',
  `enable_cod` tinyint DEFAULT '1' COMMENT '启用货到付款',
  `enable_ticket_online` tinyint DEFAULT '1' COMMENT '启用线上购买水票',
  `enable_ticket_offline` tinyint DEFAULT '1' COMMENT '启用线下水票录入',
  `enable_credit` tinyint DEFAULT '0' COMMENT '启用挂账(月结)',
  `enable_mixed` tinyint DEFAULT '0' COMMENT '启用混合支付',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站点支付配置表';

-- ============================================================
-- 27. stock_transfer (调拨记录)
-- ============================================================
CREATE TABLE `stock_transfer` (
  `id` int NOT NULL AUTO_INCREMENT,
  `from_station_id` int NOT NULL,
  `to_station_id` int NOT NULL,
  `water_type_id` int NOT NULL,
  `quantity` int NOT NULL,
  `status` tinyint DEFAULT '1',
  `approve_note` varchar(200) DEFAULT NULL,
  `complete_note` varchar(200) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_from_station` (`from_station_id`),
  KEY `idx_to_station` (`to_station_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 28. ticket_account (水票账户)
-- ============================================================
CREATE TABLE `ticket_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL COMMENT '商品ID(桶装水)',
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID',
  `remain_quantity` int NOT NULL DEFAULT '0' COMMENT '剩余水票数',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_product_station` (`customer_id`,`product_id`,`station_id`),
  KEY `idx_ticket_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 29. ticket_record (水票流水)
-- ============================================================
CREATE TABLE `ticket_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `water_type_id` bigint NOT NULL,
  `increase_qty` int DEFAULT '0' COMMENT '增加数量',
  `decrease_qty` int DEFAULT '0' COMMENT '减少数量',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单',
  `source` varchar(50) DEFAULT NULL COMMENT '来源：购卡/订水/其他',
  `ticket_source` tinyint DEFAULT '1' COMMENT '来源: 1=线上 2=线下',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- 30. user_token (用户Token)
-- ============================================================
CREATE TABLE `user_token` (
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
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Token表';

-- ============================================================
-- 31. audit_log (操作日志)
-- ============================================================
CREATE TABLE `audit_log` (
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
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志表';

SET FOREIGN_KEY_CHECKS = 1;
