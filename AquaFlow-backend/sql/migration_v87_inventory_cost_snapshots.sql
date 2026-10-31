-- 先核对当前目标库并正常备份。分段执行，不能整份自动连跑；历史记录不回填。
SELECT DATABASE() AS target_database;
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('inventory','inventory_record') AND COLUMN_NAME='cost_amount';
-- 仅inventory缺列时单独执行
ALTER TABLE inventory ADD COLUMN cost_amount DECIMAL(18,2) NULL COMMENT '当前库存总成本；NULL含未知成本';
-- 仅inventory_record缺列时单独执行
ALTER TABLE inventory_record ADD COLUMN cost_amount DECIMAL(18,2) NULL COMMENT '变动成本快照；NULL未知，历史不回填';
-- 最后只读验证两行，DECIMAL(18,2)，IS_NULLABLE=YES
SELECT TABLE_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('inventory','inventory_record') AND COLUMN_NAME='cost_amount';
