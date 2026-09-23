-- STATUS: DUPLICATE of add_openid.sql
-- customer.openid 列已在 add_openid.sql 中添加，此文件重复，新环境无需执行。

ALTER TABLE customer ADD COLUMN openid VARCHAR(100) DEFAULT NULL COMMENT 'wechat openid' AFTER tags;
