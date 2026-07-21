ALTER TABLE payment_record ADD COLUMN water_amount DECIMAL(10,2) DEFAULT 0 COMMENT '水费金额' AFTER amount;
ALTER TABLE payment_record ADD COLUMN barrel_deposit DECIMAL(10,2) DEFAULT 0 COMMENT '桶押金金额' AFTER water_amount;
ALTER TABLE payment_record ADD COLUMN excess_barrels INT DEFAULT 0 COMMENT '超出桶数' AFTER barrel_deposit;
