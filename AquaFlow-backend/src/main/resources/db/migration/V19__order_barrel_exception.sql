-- V19: Unified Order Barrel Exception Tables
-- Date: 2026-08-31

-- 1. Order Barrel Exception Table
CREATE TABLE `order_barrel_exception` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Exception ID',
    `order_id` BIGINT NOT NULL COMMENT 'Order ID',
    `customer_id` BIGINT NOT NULL COMMENT 'Customer ID',
    `station_id` BIGINT NOT NULL COMMENT 'Station ID',
    `delivery_staff_id` BIGINT DEFAULT NULL COMMENT 'Delivery Staff ID',
    
    -- Return barrel core data
    `delivery_qty` INT NOT NULL DEFAULT 0 COMMENT 'Expected delivery quantity',
    `return_qty` INT NOT NULL DEFAULT 0 COMMENT 'Actual return quantity',
    `discrepancy` INT NOT NULL DEFAULT 0 COMMENT 'Difference = delivery - return (positive=shortage, negative=overage)',
    
    -- Exception category
    `category` VARCHAR(32) NOT NULL COMMENT 'Category: RETURN_SHORT/RETURN_OVER/RETURN_REFUSE/RETURN_DAMAGE/STATION_SHORTAGE/CUSTOMER_REFUSE/OTHER',
    `type` VARCHAR(32) DEFAULT NULL COMMENT 'Specific type',
    
    -- Delivery staff field record
    `staff_action` VARCHAR(16) DEFAULT 'FULL' COMMENT 'Field action: FULL/PARTIAL/REFUSE/OWE',
    `staff_note` VARCHAR(500) DEFAULT NULL COMMENT 'Delivery staff note',
    `water_given` INT DEFAULT 0 COMMENT 'Actual water given quantity',
    `water_owed` INT DEFAULT 0 COMMENT 'Owed water quantity',
    
    -- Station manager mediation decision
    `manager_action` VARCHAR(16) DEFAULT 'IGNORE' COMMENT 'Mediation action: REFUND_TICKET/REFUND_CASH/WAIVE_DEPOSIT/ADJUST_ASSET/RESCHEDULE/IGNORE',
    `refund_ticket_qty` INT DEFAULT 0 COMMENT 'Refund ticket quantity',
    `refund_cash_amount` DECIMAL(10,2) DEFAULT NULL COMMENT 'Refund cash amount',
    `adjust_asset_qty` INT DEFAULT 0 COMMENT 'Adjust barrel asset quantity (positive/negative)',
    `adjust_product_id` BIGINT DEFAULT NULL COMMENT 'Adjusted product ID',
    `manager_note` VARCHAR(500) DEFAULT NULL COMMENT 'Station manager note',
    
    -- System compensation suggestion
    `suggested_ticket_qty` INT DEFAULT 0 COMMENT 'Suggested refund ticket quantity',
    `suggested_cash_amount` DECIMAL(10,2) DEFAULT NULL COMMENT 'Suggested refund cash amount',
    
    -- Status flow
    `status` VARCHAR(16) NOT NULL DEFAULT 'STAFF_RECORDED' COMMENT 'Status: STAFF_RECORDED/MANAGER_PENDING/MANAGER_APPROVED/EXECUTING/EXECUTED/IGNORED',
    
    -- Timestamps
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `decided_at` DATETIME DEFAULT NULL COMMENT 'Manager decision time',
    `executed_at` DATETIME DEFAULT NULL COMMENT 'Execution complete time',
    
    PRIMARY KEY (`id`),
    INDEX `idx_order` (`order_id`),
    INDEX `idx_station_status` (`station_id`, `status`),
    INDEX `idx_customer_station` (`customer_id`, `station_id`),
    INDEX `idx_staff_created` (`delivery_staff_id`, `created_at`),
    INDEX `idx_category_status` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Order barrel exception record table';

-- 2. Station Exception Config Table
CREATE TABLE `station_exception_config` (
    `station_id` BIGINT NOT NULL COMMENT 'Station ID',
    `compensation_priority` JSON DEFAULT NULL COMMENT 'Compensation priority: ["REFUND_TICKET","REFUND_CASH","WAIVE_DEPOSIT"]',
    `auto_suggest_rules` JSON DEFAULT NULL COMMENT 'Auto suggest rules',
    `notify_templates` JSON DEFAULT NULL COMMENT 'Notification templates',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Station exception config table';

-- 3. Extend orders table: exception flag fields
ALTER TABLE `orders` 
    ADD COLUMN `exception_flag` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Has exception' AFTER `barrel_discrepancy_note`,
    ADD COLUMN `exception_category` VARCHAR(32) DEFAULT NULL COMMENT 'First exception category' AFTER `exception_flag`,
    ADD COLUMN `exception_count` INT NOT NULL DEFAULT 0 COMMENT 'Exception count' AFTER `exception_category`,
    ADD COLUMN `barrel_exception_id` BIGINT DEFAULT NULL COMMENT 'Related barrel exception record ID' AFTER `exception_count`;

-- 4. In-transit barrel asset table (order pre-increment, confirm on delivery, cancel release)
CREATE TABLE `customer_barrel_in_transit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `customer_id` BIGINT NOT NULL COMMENT 'Customer ID',
    `station_id` BIGINT NOT NULL COMMENT 'Station ID',
    `product_id` BIGINT NOT NULL COMMENT 'Product ID',
    `qty` INT NOT NULL DEFAULT 0 COMMENT 'In-transit barrel quantity',
    `related_order_id` BIGINT DEFAULT NULL COMMENT 'Related order ID',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'Status: PENDING/DELIVERED/CANCELLED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    INDEX `idx_customer_station` (`customer_id`, `station_id`),
    INDEX `idx_order` (`related_order_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Customer in-transit barrel asset table';

-- 5. Extend deposit_record type (add PREPAID prepaid deposit)
-- Original types: 1=new barrel deposit, 2=return barrel, 3=lost, 4=damaged, 5=compensation, 6=manual adjust
-- New: 7=prepaid deposit (order prepaid), 8=return barrel refund, 9=exception compensation refund
-- Comment only, actual type distinguished by value

-- 6. Insert default station config (optional, initialize as needed)
-- INSERT INTO `station_exception_config` (`station_id`, `compensation_priority`, `auto_suggest_rules`)
-- VALUES (1, '["REFUND_TICKET","REFUND_CASH","WAIVE_DEPOSIT"]', '{"RETURN_SHORT":{"per_barrel":{"ticket":1,"cash":0}}}')
-- ON DUPLICATE KEY UPDATE `compensation_priority`=VALUES(`compensation_priority`), `auto_suggest_rules`=VALUES(`auto_suggest_rules`);

-- 7. Extend orders table for customer_barrel_in_transit
ALTER TABLE `orders` 
    ADD COLUMN `in_transit_flag` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Has in-transit barrels' AFTER `barrel_exception_id`;