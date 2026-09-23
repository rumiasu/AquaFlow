-- =============================================================================
-- 桶权益模型 v4：物理桶守恒（E5）的迁移基线
--
-- 问题：E5 用流水重算「顾客手上有几个桶」，但 2026-09-11 之前的配送
--      从来没写过 barrel_record（type=8 是本轮才加的）。
--      于是老客户的账面占用是 1/2，流水重算是 0，等式必然不平——
--      它忠实地报告了"没有证据"，不是算错了。
--
-- 处理：给「有账面占用、但完全没有 type=8 流水」的组合补一条基线记录，
--      delivered_qty = 当前占用，returned_qty = 0，注明是迁移基线。
--      补完之后，存量与流水对齐；此后每一笔真实配送都会继续累加流水，
--      E5 从今天起成为一条真正有效的校验。
--
-- 幂等：client_token 唯一（uk_record_client_token），重复执行用 INSERT IGNORE 静默跳过。
-- =============================================================================

INSERT IGNORE INTO barrel_record
    (customer_id, station_id, product_id, type, quantity, note, create_time,
     status, deposit_refund, client_token, over_before, over_after, delivered_qty, returned_qty)
SELECT
    a.customer_id,
    a.station_id,
    a.product_id,
    8,                                  -- 配送收发明细
    a.quantity + COALESCE(o.over_qty, 0),  -- 占用 = 权益 + over
    '迁移基线: 2026-09-11 之前的配送未留流水, 以账面占用作为守恒起点',
    NOW(),
    3,                                  -- 即时生效
    0,
    CONCAT('MIGRATE-E5-', a.customer_id, '-', a.station_id, '-', a.product_id),
    COALESCE(o.over_qty, 0),
    COALESCE(o.over_qty, 0),
    a.quantity + COALESCE(o.over_qty, 0),  -- delivered = 占用
    0                                      -- returned = 0
FROM customer_barrel_asset a
LEFT JOIN customer_barrel_over o
       ON o.customer_id = a.customer_id
      AND o.station_id  = a.station_id
      AND o.product_id  = a.product_id
WHERE (a.quantity + COALESCE(o.over_qty, 0)) > 0
  AND NOT EXISTS (SELECT 1 FROM barrel_record r
                   WHERE r.customer_id = a.customer_id
                     AND r.station_id  = a.station_id
                     AND r.product_id  = a.product_id
                     AND r.type = 8);

-- 自检：E5 应为 0
SELECT 'E5 不平条数' AS k, COUNT(*) AS v
FROM (
    SELECT u.customer_id, u.station_id, u.product_id
    FROM (
        SELECT customer_id, station_id, product_id, (delivered_qty - returned_qty) AS delta, 0 AS book FROM barrel_record WHERE type = 8
        UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 7
        UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 2 AND status = 3
        UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type IN (3, 4)
        UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 1
        UNION ALL SELECT a.customer_id, a.station_id, a.product_id, 0,
                         a.quantity + COALESCE(o.over_qty, 0)
               FROM customer_barrel_asset a
               LEFT JOIN customer_barrel_over o ON o.customer_id = a.customer_id
                    AND o.station_id = a.station_id AND o.product_id = a.product_id
    ) u
    GROUP BY u.customer_id, u.station_id, u.product_id
    HAVING COALESCE(SUM(u.delta), 0) <> COALESCE(MAX(u.book), 0)
) x;
