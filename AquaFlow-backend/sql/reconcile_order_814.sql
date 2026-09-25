-- STATUS: ONE_TIME_DATA_FIX
-- 一次性数据修复脚本，禁止在新环境执行。

-- 对账：order 814（水1×2，水票支付）补记水票消费流水 + 扣减余额 + 刷新客户统计
-- 说明：后端 createPayment 不调用 lockTicketPayment/deductTickets（死代码），此处手工对平账目

SET @orderId = 814;
SET @customerId = 86;
SET @waterTypeId = 1;
SET @qty = 2;

-- 1. 补记水票消费流水（increase=0, decrease=2, source='order'）
INSERT INTO ticket_record (customer_id, water_type_id, increase_qty, decrease_qty, order_id, source, ticket_source, create_time)
SELECT * FROM (SELECT @customerId, @waterTypeId, 0, @qty, @orderId, 'order', 1, NOW()) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_record
    WHERE customer_id=@customerId AND water_type_id=@waterTypeId
      AND decrease_qty=@qty AND order_id=@orderId AND source='order'
);
SET @applied = ROW_COUNT();

-- 2. 扣减水票余额（仅在本次未扣减时才扣；余额以流水净额为准兜底）
UPDATE ticket_account ta
SET ta.remain_quantity = COALESCE(
        (SELECT SUM(increase_qty)-SUM(decrease_qty) FROM ticket_record tr WHERE tr.customer_id=ta.customer_id AND tr.water_type_id=ta.water_type_id),
        0)
WHERE ta.customer_id = @customerId AND ta.water_type_id = @waterTypeId;

-- 3. 刷新客户统计（与种子脚本口径一致：已完成订单的已支付金额）
UPDATE customer c
SET c.total_orders = (
        SELECT COUNT(*) FROM orders o WHERE o.customer_id = @customerId AND o.status = 3
    ),
    c.total_consumption = (
        SELECT COALESCE(SUM(p.amount), 0)
        FROM payment_record p INNER JOIN orders o ON p.order_id = o.id
        WHERE o.customer_id = @customerId AND o.status = 3 AND p.status = 2
    ),
    c.avg_cycle_days = (
        SELECT CASE WHEN COUNT(*) > 1 THEN ROUND(DATEDIFF(MAX(create_time), MIN(create_time)) / (COUNT(*)-1)) ELSE NULL END
        FROM orders WHERE customer_id = @customerId AND status = 3
    ),
    c.first_order_time = (
        SELECT MIN(create_time) FROM orders WHERE customer_id = @customerId AND status = 3
    ),
    c.last_delivery_time = (
        SELECT MAX(o.update_time) FROM orders o
        WHERE o.customer_id = @customerId AND o.status = 3
    ),
    -- 4. 押金余额与 deposit_record 流水对平（订单814多收1桶押金30，补记流水）
    c.deposit_balance = (
        SELECT IFNULL(SUM(CASE WHEN type=1 THEN amount WHEN type=2 THEN -amount ELSE 0 END),0)
        FROM deposit_record WHERE customer_id = @customerId
    )
WHERE c.id = @customerId;

-- 5. 补记订单814的超桶押金流水（配送完成只写了 payment_record，未写 deposit_record）
INSERT INTO deposit_record (customer_id, type, amount, note, create_time)
SELECT @customerId, 1, 30.00, CONCAT('订单', @orderId, '超桶1个押金'), NOW()
WHERE NOT EXISTS (SELECT 1 FROM deposit_record WHERE customer_id=@customerId AND note LIKE CONCAT('订单', @orderId, '%'));

-- 6. 重新对平（放在补记之后）
UPDATE customer c
SET c.deposit_balance = (
        SELECT IFNULL(SUM(CASE WHEN type=1 THEN amount WHEN type=2 THEN -amount ELSE 0 END),0)
        FROM deposit_record WHERE customer_id = @customerId
    )
WHERE c.id = @customerId;
