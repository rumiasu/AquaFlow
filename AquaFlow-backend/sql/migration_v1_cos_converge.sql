-- ============================================================
-- V1 收敛: COS 文件存储统一迁移
-- ============================================================
-- 执行前: DESCRIBE file_info; DESCRIBE order_image; DESCRIBE product;
-- ============================================================

-- 1. file_info: 删除 url 列（只保留 object_name）
ALTER TABLE `file_info` DROP COLUMN `url`;

-- 2. order_image: url → object_name
ALTER TABLE `order_image` CHANGE COLUMN `url` `object_name` varchar(500) NOT NULL COMMENT 'COS 对象键';

-- 3. product: image_url → image_object_name
ALTER TABLE `product` CHANGE COLUMN `image_url` `image_object_name` varchar(500) DEFAULT NULL COMMENT '图片 COS 对象键';

-- 4. 验证
DESCRIBE `file_info`;
DESCRIBE `order_image`;
DESCRIBE `product`;
