-- Migration v20: 地址省市区拆分
-- 地址表增加 province/city/district 字段，与 detail 分离

ALTER TABLE address
  ADD COLUMN province VARCHAR(50) DEFAULT NULL COMMENT '省' AFTER phone,
  ADD COLUMN city VARCHAR(50) DEFAULT NULL COMMENT '市' AFTER province,
  ADD COLUMN district VARCHAR(50) DEFAULT NULL COMMENT '区/县' AFTER city;

-- 把现有 detail 中的省市区信息提取出来（如果格式为"XX省XX市XX区XX路XX号"）
-- 这里只加字段，不迁移旧数据，旧数据 detail 保持不变
