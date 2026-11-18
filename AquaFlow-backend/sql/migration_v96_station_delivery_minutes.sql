-- 仅调整新站默认打烊时间；保留已有水站时间和已接订单的时限。
-- 历史闭店承诺通过原小时列读取，首次保存后才写入精确分钟。
ALTER TABLE station
    ALTER COLUMN business_closes_at SET DEFAULT '17:00:00',
    ADD COLUMN closed_delivery_minutes INT DEFAULT NULL COMMENT '打烊接单后从下次开门起算的配送分钟数';
