-- AquaFlow 客户表增加微信openid字段
ALTER TABLE customer ADD COLUMN `openid` VARCHAR(100) DEFAULT NULL COMMENT '微信openid' AFTER `tags`;
CREATE UNIQUE INDEX idx_customer_openid ON customer(openid);
