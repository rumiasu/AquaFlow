-- 欠桶模块：记录客户在各水站的欠桶/多还桶数（按订单级别总计）
CREATE TABLE IF NOT EXISTS `customer_barrel_owed` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '客户ID',
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `owed_qty` int NOT NULL DEFAULT 0 COMMENT '欠桶数(正数=客户欠桶, 负数=客户多还)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_barrel_owed` (`customer_id`,`station_id`),
  KEY `idx_barrel_owed_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户欠桶记录';
