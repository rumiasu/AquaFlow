-- V22 修复：订单项记录实际扣减量（库存回补防刷）
--
-- 背景：
-- 下单时若库存不足，只扣「实际有货的数量」（stock），但此前取消/退款按「订单数量」全额回补，
-- 反复下单-取消即可刷出无限库存（C4）。这里把实际扣减量落库，回补时以此为据。
--
-- 注意：payment_record 的购票标识列在库中已存在，名为 ticket_water_type_id（由更早的迁移脚本创建），
-- 本脚本不再重复添加，仅补充 order_item.deducted_qty。

ALTER TABLE order_item ADD COLUMN deducted_qty INT DEFAULT NULL COMMENT '下单时实际扣减的库存数量（库存不足时小于 quantity）';

-- 历史数据兜底：已存在的订单项视作按订单数量扣减，与修复前行为一致
UPDATE order_item SET deducted_qty = quantity WHERE deducted_qty IS NULL;
