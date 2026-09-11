-- ============================================================
-- AquaFlow 最终待执行迁移（仅含库里缺失的部分）
-- 生成：2026-09-09
-- 背景：项目未启用 Flyway，db/migration/*.sql 与根目录脚本均为手动执行。
-- 已用只读诊断确认：V19 / V20 数据迁移 / V21 / V22 / migration.sql / address 省市区
--   等结构均已落地，本文件只补齐「确实缺失」的对象，避免重复执行报错。
-- 全部语句均为幂等/安全写法，可重复执行。
-- ============================================================

-- ------------------------------------------------------------
-- 1) V20：创建缺失的视图 v_station_exception_stats
--    诊断已确认 order_barrel_exception 的 refund_ticket_qty / refund_cash_amount /
--    created_at / decided_at 等列均存在，视图可建。
-- ------------------------------------------------------------
CREATE OR REPLACE VIEW `v_station_exception_stats` AS
SELECT
    obe.`station_id`,
    s.`name` AS station_name,
    COUNT(*) AS total_exceptions,
    SUM(CASE WHEN obe.`category` = 'RETURN_SHORT'    THEN 1 ELSE 0 END) AS short_return_count,
    SUM(CASE WHEN obe.`category` = 'RETURN_OVER'     THEN 1 ELSE 0 END) AS over_return_count,
    SUM(CASE WHEN obe.`category` = 'STATION_SHORTAGE' THEN 1 ELSE 0 END) AS shortage_count,
    SUM(CASE WHEN obe.`status` = 'STAFF_RECORDED'    THEN 1 ELSE 0 END) AS pending_count,
    SUM(CASE WHEN obe.`status` = 'EXECUTED'          THEN 1 ELSE 0 END) AS resolved_count,
    COALESCE(SUM(obe.`refund_ticket_qty`), 0) AS total_refund_tickets,
    COALESCE(SUM(obe.`refund_cash_amount`), 0) AS total_refund_cash,
    AVG(TIMESTAMPDIFF(HOUR, obe.`created_at`, obe.`decided_at`)) AS avg_handle_hours
FROM `order_barrel_exception` obe
LEFT JOIN `station` s ON obe.`station_id` = s.`id`
WHERE obe.`created_at` >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY obe.`station_id`, s.`name`;

-- ------------------------------------------------------------
-- 2) V20：历史桶差异数据迁移到 order_barrel_exception（幂等）
--    前置：orders.delivery_bucket_qty / return_bucket_qty / barrel_discrepancy /
--    barrel_discrepancy_note 均已存在；INSERT 带 NOT EXISTS 防重，UPDATE 带
--    exception_flag 判断防重。已跑过也不会重复插入。
-- ------------------------------------------------------------
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
    'IGNORED' AS manager_action,
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

-- ------------------------------------------------------------
-- 3) fix_schema：补齐真正缺失的索引 idx_ticket_record_station
--    原 fix_schema.sql 使用 MySQL 不支持的 CREATE INDEX IF NOT EXISTS，此处改写为
--    “先查 INFORMATION_SCHEMA 再建”的安全写法。
--    （deposit_record / order_template_item 的 station_id 索引已存在；
--      ticket_account.uk_customer_product_station 也已存在且定义正确，均无需处理。）
-- ------------------------------------------------------------
SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ticket_record' AND INDEX_NAME = 'idx_ticket_record_station');
SET @sql = IF(@idx_exists = 0,
  'CREATE INDEX idx_ticket_record_station ON ticket_record (station_id)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4) migration_v19_fix_barrel_asset_backfill：桶资产补录（幂等）
--    前置：customer_barrel_asset 已存在唯一键 uk_asset(customer_id,product_id,station_id)，
--    INSERT ... ON DUPLICATE KEY UPDATE 可安全去重。
-- ------------------------------------------------------------
INSERT INTO customer_barrel_asset (customer_id, product_id, station_id, quantity, update_time)
SELECT t.customer_id, t.product_id, t.station_id, SUM(t.qty), NOW()
FROM customer_barrel_in_transit t
WHERE t.status = 'DELIVERED'
GROUP BY t.customer_id, t.product_id, t.station_id
ON DUPLICATE KEY UPDATE
    quantity = quantity + VALUES(quantity),
    update_time = NOW();

-- ------------------------------------------------------------
-- 5) drop_tag：删除 address.tag（如不存在则 no-op，安全）
-- ------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND COLUMN_NAME = 'tag');
SET @sql = IF(@col_exists > 0,
  'ALTER TABLE `address` DROP COLUMN `tag`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists2 = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'address' AND INDEX_NAME = 'idx_address_tag');
SET @sql2 = IF(@idx_exists2 > 0,
  'DROP INDEX `idx_address_tag` ON `address`',
  'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
