ALTER TABLE station
    ADD COLUMN business_opens_at TIME NOT NULL DEFAULT '08:00:00' COMMENT '每日开门时间',
    ADD COLUMN business_closes_at TIME NOT NULL DEFAULT '20:00:00' COMMENT '每日打烊时间，可跨夜',
    ADD COLUMN open_delivery_minutes INT NOT NULL DEFAULT 60 COMMENT '营业中接单后的配送分钟数',
    ADD COLUMN closed_delivery_hours INT NOT NULL DEFAULT 2 COMMENT '打烊接单后从下次开门起算的配送小时数';
