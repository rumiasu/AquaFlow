-- 清空所有表数据（表结构不动，自增ID重置为1）
SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM order_image;
DELETE FROM order_item;
DELETE FROM order_template_item;
DELETE FROM order_template;
DELETE FROM orders;

DELETE FROM barrel_record;
DELETE FROM customer_barrel_asset;
DELETE FROM deposit_record;
DELETE FROM payment_record;
DELETE FROM ticket_record;
DELETE FROM ticket_account;

DELETE FROM feedback;
DELETE FROM notice;
DELETE FROM audit_log;

DELETE FROM inventory;
DELETE FROM product;
DELETE FROM water_type;

DELETE FROM address;
DELETE FROM customer;

DELETE FROM staff;
DELETE FROM station;

DELETE FROM file_info;
DELETE FROM user_token;

-- 重置所有表的自增ID为1
ALTER TABLE order_image AUTO_INCREMENT = 1;
ALTER TABLE order_item AUTO_INCREMENT = 1;
ALTER TABLE order_template_item AUTO_INCREMENT = 1;
ALTER TABLE order_template AUTO_INCREMENT = 1;
ALTER TABLE orders AUTO_INCREMENT = 1;

ALTER TABLE barrel_record AUTO_INCREMENT = 1;
ALTER TABLE customer_barrel_asset AUTO_INCREMENT = 1;
ALTER TABLE deposit_record AUTO_INCREMENT = 1;
ALTER TABLE payment_record AUTO_INCREMENT = 1;
ALTER TABLE ticket_record AUTO_INCREMENT = 1;
ALTER TABLE ticket_account AUTO_INCREMENT = 1;

ALTER TABLE feedback AUTO_INCREMENT = 1;
ALTER TABLE notice AUTO_INCREMENT = 1;
ALTER TABLE audit_log AUTO_INCREMENT = 1;

ALTER TABLE inventory AUTO_INCREMENT = 1;
ALTER TABLE product AUTO_INCREMENT = 1;
ALTER TABLE water_type AUTO_INCREMENT = 1;

ALTER TABLE address AUTO_INCREMENT = 1;
ALTER TABLE customer AUTO_INCREMENT = 1;

ALTER TABLE staff AUTO_INCREMENT = 1;
ALTER TABLE station AUTO_INCREMENT = 1;

ALTER TABLE file_info AUTO_INCREMENT = 1;
ALTER TABLE user_token AUTO_INCREMENT = 1;

SET FOREIGN_KEY_CHECKS = 1;
