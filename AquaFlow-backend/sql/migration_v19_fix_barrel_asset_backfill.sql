-- Migration v19: 补录历史已完成订单的桶资产
-- 问题：completeOrder 只将 in_transit 标为 DELIVERED，但未转入 customer_barrel_asset
-- 影响：用户付了押金，系统却认为持有桶为0，下次复购重复收押金

-- 1. 将已 DELIVERED 的在途记录插入/合并到持有桶资产
INSERT INTO customer_barrel_asset (customer_id, product_id, station_id, quantity, update_time)
SELECT t.customer_id, t.product_id, t.station_id, SUM(t.qty), NOW()
FROM customer_barrel_in_transit t
WHERE t.status = 'DELIVERED'
GROUP BY t.customer_id, t.product_id, t.station_id
ON DUPLICATE KEY UPDATE
    quantity = quantity + VALUES(quantity),
    update_time = NOW();

-- 2. 验证结果：查看补录后的桶资产
SELECT a.customer_id, a.product_id, a.station_id, a.quantity,
       p.name AS product_name, c.name AS customer_name
FROM customer_barrel_asset a
LEFT JOIN product p ON a.product_id = p.id
LEFT JOIN customer c ON a.customer_id = c.id
ORDER BY a.update_time DESC;
