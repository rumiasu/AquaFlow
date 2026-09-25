-- ============================================================
-- AquaFlow v1 最终数据库结构
-- 生成时间: 2026-08-24 (V1 Binding 模型定稿)
-- 用途: 全新空数据库初始化
-- 核心规则:
--   身份归属  唯一以 staff.station_id 为准 (NULL = 未绑定水站)
--   申请历史  以 staff_station_application 为准
--   禁止字段  apply_station_id, binding_status, station.manager_name, factory_id, password明文, water_type
--   禁止角色  FACTORY_ADMIN / factory / manager / customer.role
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. station (水站)
-- 站长关系改由 staff.role='STATION_MANAGER' AND staff.station_id=station.id 表达
-- station 表本身不保存 manager 字段
-- ============================================================
CREATE TABLE `station` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `address` varchar(200) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1 营业 2 停业',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水站表';

-- ============================================================
-- 2. staff (员工)
-- 站长 STATION_MANAGER 和配送员 DELIVERY 统一在这里
-- station_id NULL 表示未绑定水站 (配送员选好身份但未绑定/解绑后)
-- openid 唯一约束用于微信账号稳定映射
-- ============================================================
CREATE TABLE `staff` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `openid` varchar(100) DEFAULT NULL COMMENT '微信openid (唯一映射)',
  `password_hash` varchar(255) DEFAULT NULL COMMENT '密码哈希 (开发账号登录用)',
  `role` varchar(30) NOT NULL COMMENT '角色: 仅允许 STATION_MANAGER / DELIVERY',
  `station_id` bigint NULL COMMENT '所属水站ID: NULL=未绑定水站; 站长创建后立即绑定; 配送员站长审批后绑定',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1 在职 2 离职',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_openid` (`openid`),
  KEY `idx_staff_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工表(站长/配送员)';

-- ============================================================
-- 3. staff_station_application (配送员-水站绑定/解绑申请表)
-- 只记录申请历史, 不决定当前归属关系
-- 当前归属关系永远由 staff.station_id 决定
-- ============================================================
CREATE TABLE `staff_station_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL COMMENT '申请人 staff.id (必须是 DELIVERY)',
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
  KEY `idx_app_station_status` (`station_id`, `status`),
  KEY `idx_app_type_status` (`type`, `status`),
  CONSTRAINT `fk_app_staff`        FOREIGN KEY (`staff_id`)        REFERENCES `staff`   (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_app_station`      FOREIGN KEY (`station_id`)      REFERENCES `station` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_app_handle_staff` FOREIGN KEY (`handle_staff_id`) REFERENCES `staff`   (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送员-水站绑定/解绑申请审批';

-- ============================================================
-- 4. customer (客户)
-- 客户永远是客户, 没有站长角色
-- ============================================================
CREATE TABLE `customer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `openid` varchar(100) DEFAULT NULL COMMENT '微信openid',
  `name` varchar(100) NOT NULL COMMENT '客户名',
  `phone` varchar(30) NOT NULL COMMENT '联系电话',
  `customer_type` tinyint NOT NULL DEFAULT 1 COMMENT '1 个人 2 企业',
  `station_id` bigint DEFAULT NULL COMMENT '当前归属水站',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `first_order_time` datetime DEFAULT NULL COMMENT '首次下单时间',
  `last_delivery_time` datetime DEFAULT NULL COMMENT '最近配送时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_openid` (`openid`),
  KEY `idx_customer_phone` (`phone`),
  KEY `idx_customer_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户表';

-- ============================================================
-- 5. address (地址)
-- ============================================================
CREATE TABLE `address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '所属客户ID',
  `name` varchar(50) NOT NULL COMMENT '收件人姓名',
  `phone` varchar(30) NOT NULL COMMENT '收件人电话',
  `label` varchar(50) DEFAULT NULL COMMENT '标签(家/公司/父母家)',
  `detail` varchar(255) NOT NULL COMMENT '详细地址(小区+楼栋+单元+门牌号)',
  `lat` decimal(10,6) DEFAULT NULL COMMENT '纬度',
  `lng` decimal(10,6) DEFAULT NULL COMMENT '经度',
  `is_default` tinyint NOT NULL DEFAULT 0 COMMENT '0 非默认 1 默认',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_address_customer` (`customer_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='地址表';

-- ============================================================
-- 6. product (商品) - 替代原 water_type
-- ============================================================
CREATE TABLE `product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '商品名称',
  `category` tinyint NOT NULL COMMENT '1 桶装水 2 瓶装水 3 饮水器',
  `brand` varchar(100) DEFAULT NULL COMMENT '品牌',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `image_object_name` varchar(500) DEFAULT NULL COMMENT '图片 COS 对象键',
  `description` text COMMENT '商品描述',
  `price` decimal(10,2) NOT NULL COMMENT '基础售价',
  `deposit` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '押金(只有桶装水使用)',
  `max_per_order` int DEFAULT NULL COMMENT '单次购买上限',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '0 下架 1 正常 2 停售',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';

-- ============================================================
-- 7. inventory (水站商品库存)
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
-- 8. orders (订单主表)
-- ============================================================
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `address_id` bigint NOT NULL COMMENT '地址ID',
  `owner_station_id` bigint DEFAULT NULL COMMENT '归属站(客户归属站)',
  `delivery_station_id` bigint DEFAULT NULL COMMENT '实际履约配送水站ID',
  `delivery_staff_id` bigint DEFAULT NULL COMMENT '配送员ID (NULL=未分配)',
  `source` tinyint NOT NULL COMMENT '来源: 1 电话 2 微信 3 小程序',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '订单状态: 1 待配送 3 配送中 4 已送达 5 已完成 6 已取消',
  `payment_method` tinyint NOT NULL COMMENT '支付方式: 1 微信支付 2 水票 3 线下支付',
  `payment_status` tinyint NOT NULL DEFAULT 1 COMMENT '1 待付款 2 已付款',
  `total_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额',
  `water_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '水费金额',
  `deposit_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '押金金额',
  `receiver_name` varchar(50) NOT NULL COMMENT '收件人姓名',
  `receiver_phone` varchar(30) NOT NULL COMMENT '收件人电话',
  `address_snapshot` varchar(500) NOT NULL COMMENT '地址快照',
  `address_snapshot_lat` decimal(10,7) DEFAULT NULL COMMENT '地址快照纬度',
  `address_snapshot_lng` decimal(10,7) DEFAULT NULL COMMENT '地址快照经度',
  `guard_info` varchar(200) DEFAULT NULL COMMENT '门卫信息',
  `delivery_time_request` varchar(100) DEFAULT NULL COMMENT '配送时间要求',
  `special_note` varchar(500) DEFAULT NULL COMMENT '特殊说明',
  `delivery_bucket_qty` int NOT NULL DEFAULT 0 COMMENT '配送桶数量',
  `return_bucket_qty` int NOT NULL DEFAULT 0 COMMENT '回收桶数量',
  `barrel_discrepancy` int NOT NULL DEFAULT 0 COMMENT '差桶数量',
  `barrel_discrepancy_note` varchar(200) DEFAULT NULL COMMENT '差桶差异说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_orders_customer` (`customer_id`),
  KEY `idx_orders_address` (`address_id`),
  KEY `idx_orders_status_time` (`status`,`create_time`),
  KEY `idx_orders_owner_station` (`owner_station_id`),
  KEY `idx_orders_delivery_station` (`delivery_station_id`),
  KEY `idx_orders_delivery_staff` (`delivery_staff_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单主表';

-- ============================================================
-- 9. order_item (订单商品明细) - 一单多商品
-- ============================================================
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `product_name_snapshot` varchar(100) NOT NULL COMMENT '商品名称快照',
  `brand_snapshot` varchar(100) DEFAULT NULL COMMENT '品牌快照',
  `spec_snapshot` varchar(100) DEFAULT NULL COMMENT '规格快照',
  `price` decimal(10,2) NOT NULL COMMENT '单价',
  `quantity` int NOT NULL COMMENT '数量',
  `deposit` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '押金',
  `subtotal` decimal(10,2) NOT NULL COMMENT '小计',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_item_order` (`order_id`),
  KEY `idx_order_item_product` (`product_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单商品明细';

-- ============================================================
-- 10. customer_barrel_asset (客户桶资产)
-- ============================================================
CREATE TABLE `customer_barrel_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `product_id` bigint NOT NULL COMMENT '商品ID(桶装水)',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `quantity` int NOT NULL DEFAULT 0 COMMENT '持有桶数',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_barrel_asset` (`customer_id`,`product_id`,`station_id`),
  KEY `idx_barrel_asset_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户桶资产';

-- ============================================================
-- 11. customer_deposit_account (押金账户)
-- ============================================================
CREATE TABLE `customer_deposit_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `balance` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '押金余额',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_deposit_account` (`customer_id`,`station_id`),
  KEY `idx_deposit_account_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='押金账户';

-- ============================================================
-- 12. deposit_record (押金流水)
-- ============================================================
CREATE TABLE `deposit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `type` tinyint NOT NULL COMMENT '1 新增押金 2 退押金 3 丢桶赔偿 4 其他调整',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_deposit_record_customer` (`customer_id`),
  KEY `idx_deposit_record_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='押金流水';

-- ============================================================
-- 13. barrel_record (桶资产异常记录)
-- ============================================================
CREATE TABLE `barrel_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `product_id` bigint NOT NULL COMMENT '商品ID(桶装水)',
  `type` tinyint NOT NULL COMMENT '1 新增押金桶 2 退桶 3 丢失 4 损坏 5 赔偿 6 人工调整',
  `quantity` int NOT NULL COMMENT '数量',
  `related_order_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_barrel_record_customer` (`customer_id`),
  KEY `idx_barrel_record_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='桶资产异常记录';

-- ============================================================
-- 14. payment_record (支付记录)
-- ============================================================
CREATE TABLE `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `amount` decimal(10,2) NOT NULL COMMENT '支付金额',
  `payment_method` tinyint NOT NULL COMMENT '支付方式: 1 微信支付 2 水票 3 线下支付',
  `status` tinyint NOT NULL COMMENT '状态: 1 待支付 2 已支付 3 已退款 4 已取消',
  `transaction_no` varchar(100) DEFAULT NULL COMMENT '交易流水号',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID',
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_payment_order` (`order_id`),
  KEY `idx_payment_customer` (`customer_id`),
  KEY `idx_payment_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付记录';

-- ============================================================
-- 15. ticket_account (水票账户)
-- ============================================================
CREATE TABLE `ticket_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `product_id` bigint NOT NULL COMMENT '商品ID(桶装水)',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `remain_quantity` int NOT NULL DEFAULT 0 COMMENT '剩余水票数',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_account` (`customer_id`,`product_id`,`station_id`),
  KEY `idx_ticket_account_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水票账户';

-- ============================================================
-- 16. ticket_record (水票流水)
-- ============================================================
CREATE TABLE `ticket_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `product_id` bigint NOT NULL COMMENT '商品ID(桶装水)',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `increase_qty` int NOT NULL DEFAULT 0 COMMENT '增加数量',
  `decrease_qty` int NOT NULL DEFAULT 0 COMMENT '减少数量',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `source` varchar(50) DEFAULT NULL COMMENT '来源说明',
  `ticket_source` tinyint DEFAULT NULL COMMENT '1 线上 2 线下',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ticket_record_customer` (`customer_id`),
  KEY `idx_ticket_record_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水票流水';

-- ============================================================
-- 17. order_template (常用订单)
-- ============================================================
CREATE TABLE `order_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '所属水站ID',
  `name` varchar(100) NOT NULL COMMENT '模板名称',
  `special_note` varchar(500) DEFAULT NULL COMMENT '特殊说明',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '0 禁用 1 启用',
  `is_default` tinyint NOT NULL DEFAULT 0 COMMENT '0 非默认 1 默认',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_template_customer` (`customer_id`),
  KEY `idx_template_station` (`station_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='常用订单';

-- ============================================================
-- 19. order_template_item
-- ============================================================
CREATE TABLE `order_template_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL COMMENT '模板ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `quantity` int NOT NULL COMMENT '数量',
  PRIMARY KEY (`id`),
  KEY `idx_template_item_template` (`template_id`),
  KEY `idx_template_item_product` (`product_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='常用订单明细';

-- ============================================================
-- 20. order_image
-- ============================================================
CREATE TABLE `order_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键',
  `type` tinyint NOT NULL COMMENT '1 正常配送 2 异常情况',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_image_order` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单图片';

-- ============================================================
-- 21. notice
-- ============================================================
CREATE TABLE `notice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint DEFAULT NULL COMMENT '所属水站ID(NULL=系统公告)',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` text NOT NULL COMMENT '内容',
  `type` tinyint NOT NULL COMMENT '1 系统公告 2 水站通知 3 活动',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '0 下架 1 发布',
  `publisher_id` bigint DEFAULT NULL COMMENT '发布者ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notice_station` (`station_id`),
  KEY `idx_notice_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告表';

-- ============================================================
-- 22. feedback
-- ============================================================
CREATE TABLE `feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint DEFAULT NULL COMMENT '提交人ID(员工)',
  `customer_id` bigint DEFAULT NULL COMMENT '客户ID(客户反馈时使用)',
  `category` varchar(50) NOT NULL COMMENT '分类',
  `content` text NOT NULL COMMENT '反馈内容',
  `contact` varchar(100) DEFAULT NULL COMMENT '联系方式',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈建议';

-- ============================================================
-- 23. file_info
-- ============================================================
CREATE TABLE `file_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `file_type` varchar(50) DEFAULT NULL COMMENT '文件类型',
  `mime_type` varchar(100) DEFAULT NULL COMMENT 'MIME类型',
  `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键',
  `category` varchar(50) DEFAULT NULL COMMENT '分类',
  `uploader_id` bigint DEFAULT NULL COMMENT '上传者ID',
  `uploader_name` varchar(50) DEFAULT NULL COMMENT '上传者姓名',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件管理';

-- ============================================================
-- 24. user_token
-- ============================================================
CREATE TABLE `user_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `user_type` varchar(20) NOT NULL COMMENT '用户类型: staff / customer',
  `refresh_token` varchar(500) NOT NULL COMMENT 'refresh_token',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `device_info` varchar(100) DEFAULT NULL COMMENT '设备标识',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_token_user` (`user_id`,`user_type`),
  KEY `idx_user_token_refresh` (`refresh_token`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Token表';

-- ============================================================
-- 25. audit_log (操作日志, 记录站长 FORCE_UNBIND 等关键动作)
-- ============================================================
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `username` varchar(50) DEFAULT NULL COMMENT '用户名',
  `role` varchar(30) DEFAULT NULL COMMENT '角色',
  `module` varchar(50) NOT NULL COMMENT '模块 (STAFF_BINDING 等)',
  `action` varchar(50) NOT NULL COMMENT '操作 (FORCE_UNBIND 等)',
  `target` varchar(100) DEFAULT NULL COMMENT '操作对象',
  `detail` text COMMENT '详情',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP地址',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_log_user` (`user_id`),
  KEY `idx_audit_log_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志表';

SET FOREIGN_KEY_CHECKS = 1;
