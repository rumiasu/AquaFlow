-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- 水类型增加图片字段
ALTER TABLE water_type ADD COLUMN image_url VARCHAR(500) DEFAULT '' COMMENT '图片URL' AFTER note;
