-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- ============================================================================
-- P1 整改：桶资产最终规则 + 订单支付状态机
-- 迁移号：v13
-- ============================================================================
-- 【名词】按 AquaFlow 桶资产最终规则（14 条）：
--   持有桶资产 = 押金桶资产（支付押金+N，退桶退押金-N），唯一权威；
--   欠桶       = 每次配送完成时"应回收-实际回收"差额（>0），纯业务记录，
--                不进入持有、不进入购买上限、后续回收时只减少欠桶。
--    单次购买上限 = water_type.max_per_order（唯一）。
-- ============================================================================

-- 1. 客户持有桶资产台账（按水类型）
CREATE TABLE IF NOT EXISTS customer_barrel_asset (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id   INT NOT NULL COMMENT '客户ID',
    water_type_id INT NOT NULL COMMENT '水类型ID',
    quantity      INT NOT NULL DEFAULT 0 COMMENT '持有桶资产数',
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_asset (customer_id, water_type_id)
) COMMENT='客户持有桶资产（押金桶，一次性回填历史后由业务维护）';

-- 2. 客户欠桶台账（按水类型，纯业务记录）
CREATE TABLE IF NOT EXISTS customer_owed_barrel (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id   INT NOT NULL COMMENT '客户ID',
    water_type_id INT NOT NULL COMMENT '水类型ID',
    quantity      INT NOT NULL DEFAULT 0 COMMENT '尚欠桶数',
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owed (customer_id, water_type_id)
) COMMENT='客户欠桶台账（配送完成差额，后续回收只减欠桶，不进持有）';

-- 3. 持有资产基线回填：历史持有 = Σ(送出-回收)（订单口径一次性基线，之后改为押金口径）
INSERT INTO customer_barrel_asset (customer_id, water_type_id, quantity)
SELECT o.customer_id, o.water_type_id,
       SUM(o.delivery_bucket_qty - o.return_bucket_qty) AS qty
FROM orders o
WHERE o.delivery_bucket_qty > 0
GROUP BY o.customer_id, o.water_type_id
HAVING qty > 0
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

-- 4. 欠桶基线回填：Σ(正数桶差异)
INSERT INTO customer_owed_barrel (customer_id, water_type_id, quantity)
SELECT o.customer_id, o.water_type_id, SUM(o.barrel_discrepancy) AS qty
FROM orders o
WHERE o.barrel_discrepancy > 0
GROUP BY o.customer_id, o.water_type_id
HAVING qty > 0
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);