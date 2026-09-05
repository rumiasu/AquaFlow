-- V20: 历史桶差异数据迁移到 order_barrel_exception 表
-- 创建时间: 2026-08-31

-- 将历史 orders 表中 barrel_discrepancy != 0 的记录迁移到 order_barrel_exception
-- 仅迁移最近 6 个月的数据，避免全量迁移压力

INSERT INTO `order_barrel_exception` (
    `order_id`, `customer_id`, `station_id`, `delivery_staff_id`,
    `delivery_qty`, `return_qty`, `discrepancy`, `category`, `type`,
    `staff_action`, `staff_note`, `water_given`, `water_owed`,
    `manager_action`, `status`, `created_at`, `decided_at`, `executed_at`
)
SELECT 
    o.`id` AS order_id,
    o.`customer_id`,
    o.`station_id`,
    o.`delivery_staff_id`,
    o.`delivery_bucket_qty` AS delivery_qty,
    o.`return_bucket_qty` AS return_qty,
    o.`barrel_discrepancy` AS discrepancy,
    CASE 
        WHEN o.`barrel_discrepancy` > 0 THEN 'RETURN_SHORT'
        WHEN o.`barrel_discrepancy` < 0 THEN 'RETURN_OVER'
        ELSE 'OTHER'
    END AS category,
    CASE 
        WHEN o.`barrel_discrepancy` > 0 THEN 'SHORT_RETURN'
        WHEN o.`barrel_discrepancy` < 0 THEN 'OVER_RETURN'
        ELSE 'OTHER'
    END AS type,
    'FULL' AS staff_action,
    o.`barrel_discrepancy_note` AS staff_note,
    o.`return_bucket_qty` AS water_given,
    0 AS water_owed,
    'IGNORED' AS manager_action,  -- 历史数据默认标记为已忽略，需人工复核
    'IGNORED' AS status,
    o.`create_time` AS created_at,
    o.`update_time` AS decided_at,
    o.`update_time` AS executed_at
FROM `orders` o
WHERE o.`barrel_discrepancy` IS NOT NULL 
  AND o.`barrel_discrepancy` != 0
  AND o.`create_time` >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
  AND NOT EXISTS (
      SELECT 1 FROM `order_barrel_exception` obe 
      WHERE obe.`order_id` = o.`id`
  );

-- 更新 orders 表的异常标记字段
UPDATE `orders` o
SET 
    o.`exception_flag` = TRUE,
    o.`exception_category` = (
        SELECT CASE 
            WHEN o.`barrel_discrepancy` > 0 THEN 'RETURN_SHORT'
            WHEN o.`barrel_discrepancy` < 0 THEN 'RETURN_OVER'
            ELSE 'OTHER'
        END
    ),
    o.`exception_count` = 1,
    o.`barrel_exception_id` = (
        SELECT obe.`id` FROM `order_barrel_exception` obe 
        WHERE obe.`order_id` = o.`id` 
        ORDER BY obe.`created_at` DESC LIMIT 1
    )
WHERE o.`barrel_discrepancy` IS NOT NULL 
  AND o.`barrel_discrepancy` != 0
  AND o.`create_time` >= DATE_SUB(NOW(), INTERVAL 6 MONTH)
  AND (o.`exception_flag` IS NULL OR o.`exception_flag` = FALSE);

-- 创建视图：站长快速查看异常统计
CREATE OR REPLACE VIEW `v_station_exception_stats` AS
SELECT 
    obe.`station_id`,
    s.`name` AS station_name,
    COUNT(*) AS total_exceptions,
    SUM(CASE WHEN obe.`category` = 'RETURN_SHORT' THEN 1 ELSE 0 END) AS short_return_count,
    SUM(CASE WHEN obe.`category` = 'RETURN_OVER' THEN 1 ELSE 0 END) AS over_return_count,
    SUM(CASE WHEN obe.`category` = 'STATION_SHORTAGE' THEN 1 ELSE 0 END) AS shortage_count,
    SUM(CASE WHEN obe.`status` = 'STAFF_RECORDED' THEN 1 ELSE 0 END) AS pending_count,
    SUM(CASE WHEN obe.`status` = 'EXECUTED' THEN 1 ELSE 0 END) AS resolved_count,
    COALESCE(SUM(obe.`refund_ticket_qty`), 0) AS total_refund_tickets,
    COALESCE(SUM(obe.`refund_cash_amount`), 0) AS total_refund_cash,
    AVG(TIMESTAMPDIFF(HOUR, obe.`created_at`, obe.`decided_at`)) AS avg_handle_hours
FROM `order_barrel_exception` obe
LEFT JOIN `station` s ON obe.`station_id` = s.`id`
WHERE obe.`created_at` >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY obe.`station_id`, s.`name`;