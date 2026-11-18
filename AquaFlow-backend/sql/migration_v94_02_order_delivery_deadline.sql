ALTER TABLE orders
    ADD COLUMN delivery_deadline DATETIME DEFAULT NULL COMMENT '接单时确定的最迟送达时间（业务时区）',
    ADD COLUMN delivery_timing_pending TINYINT(1) NOT NULL DEFAULT 0 COMMENT '待预付款成功后确定配送时限';
