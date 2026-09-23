-- =============================================================================
-- [AQ-009] 押金入账时点迁移：下单 → 支付成功
-- 背景：旧实现在 createOrder 里"下单即 increaseBalance 并写 type=5 预收押金流水"，
--       此时顾客一分钱未付。新实现改为支付成功时才入账（applyDepositOnPaid，按
--       related_order_id 幂等）。本脚本负责把存量数据对齐到新口径。
-- 幂等：可重复执行（第 2 步用 note 的 [已回退] 后缀做标记，避免重复回退）。
-- 执行前请备份 deposit_record / customer_deposit_account。
-- =============================================================================

-- 备份
CREATE TABLE IF NOT EXISTS deposit_record_bak_aq009 AS SELECT * FROM deposit_record;
CREATE TABLE IF NOT EXISTS customer_deposit_account_bak_aq009 AS SELECT * FROM customer_deposit_account;

-- -----------------------------------------------------------------------------
-- 1) 已付款订单：把历史 type=5 预收押金流水关联到对应订单（建立幂等键）
--    目的：防止这些订单未来再次触发 applyDepositOnPaid 时重复入账。
--    匹配规则：同客户、同站、金额等于订单 deposit_amount、订单已付款。
-- -----------------------------------------------------------------------------
UPDATE deposit_record dr
JOIN (
  SELECT d.id AS dr_id,
         (SELECT o.id FROM orders o
           WHERE o.customer_id = d.customer_id
             AND o.station_id   = d.station_id
             AND o.payment_status = 2
             AND o.deposit_amount = d.amount
           ORDER BY o.id DESC LIMIT 1) AS oid
  FROM deposit_record d
  WHERE d.type = 5 AND d.related_order_id IS NULL AND d.note = '下单预收桶押金'
) m ON m.dr_id = dr.id
SET dr.related_order_id = m.oid
WHERE m.oid IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 2) 未付款订单的预收押金：回退（客户一分未付，不应占用押金余额）
--    2a) 写一条 CANCEL_PREPAID(8) 反向流水；2b) 扣减对应押金账户余额；
--    2c) 给原流水打 [已回退] 标记，保证脚本可重复执行不重复回退。
-- -----------------------------------------------------------------------------
INSERT INTO deposit_record (customer_id, station_id, type, amount, related_order_id, note, operator_id, create_time)
SELECT dr.customer_id, dr.station_id, 8, -dr.amount, NULL,
       CONCAT('迁移:回退未付款订单预收押金(ref=', dr.id, ')'), NULL, NOW()
FROM deposit_record dr
WHERE dr.type = 5 AND dr.related_order_id IS NULL
  AND dr.note = '下单预收桶押金';

UPDATE customer_deposit_account a
JOIN (
  SELECT customer_id, station_id, SUM(amount) AS back_amount
  FROM deposit_record
  WHERE type = 5 AND related_order_id IS NULL AND note = '下单预收桶押金'
  GROUP BY customer_id, station_id
) t ON t.customer_id = a.customer_id AND t.station_id = a.station_id
SET a.balance = a.balance - t.back_amount, a.update_time = NOW();

UPDATE deposit_record
SET note = CONCAT(note, '[已回退]')
WHERE type = 5 AND related_order_id IS NULL AND note = '下单预收桶押金';

-- -----------------------------------------------------------------------------
-- 校验：押金账户余额 vs 流水净和（应相等）
-- -----------------------------------------------------------------------------
SELECT '=== AQ-009 迁移后校验：余额 vs 流水净和 ===' AS t;
SELECT a.customer_id, a.station_id, a.balance AS account_balance,
       COALESCE(r.flow_sum, 0) AS flow_sum,
       ROUND(a.balance - COALESCE(r.flow_sum, 0), 2) AS diff
FROM customer_deposit_account a
LEFT JOIN (SELECT customer_id, station_id, SUM(amount) AS flow_sum FROM deposit_record GROUP BY customer_id, station_id) r
       ON r.customer_id = a.customer_id AND r.station_id = a.station_id;

SELECT 'AQ-009 押金入账时点迁移完成' AS result;
