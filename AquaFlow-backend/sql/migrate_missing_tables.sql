CREATE TABLE IF NOT EXISTS `product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `category` tinyint NOT NULL,
  `brand` varchar(100) DEFAULT NULL,
  `spec` varchar(100) DEFAULT NULL,
  `image_url` varchar(500) DEFAULT NULL,
  `description` text,
  `price` decimal(10,2) NOT NULL,
  `deposit` decimal(10,2) NOT NULL DEFAULT 0.00,
  `max_per_order` int DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `product_name` varchar(100) DEFAULT NULL,
  `quantity` int NOT NULL DEFAULT 1,
  `unit_price` decimal(10,2) NOT NULL,
  `line_total` decimal(10,2) NOT NULL,
  `barrel_qty` int DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `product` (`id`, `name`, `category`, `brand`, `spec`, `image_url`, `description`, `price`, `deposit`, `max_per_order`, `status`, `sort`, `create_time`, `update_time`)
SELECT `id`, `name`, 1, `brand`, `spec`, `image_url`, `description`, `price`, `deposit`, `max_per_order`, CASE `status` WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END, COALESCE(`sort`, 0), `create_time`, `update_time`
FROM `water_type`
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);
