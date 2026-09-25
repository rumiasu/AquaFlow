-- =============================================================================
-- AquaFlow 日结自动对账（daily reconcile）
-- 对应审计报告第 7 节 3 条守恒等式：
--   等式1：押金/余额账户余额  ==  该账户全部流水之和（按 type 符号口径）
--   等式2：订单 payment_status  ==  payment_record 汇总口径
--   等式3：桶三态守恒（在手 + 配送中 + 欠）与物理桶/押金折算一致
--
-- 用法（可挂到 crontab / 定时任务）：
--   mysql -h127.0.0.1 -uroot -p aquaflow < scripts/daily_reconcile.sql
-- 输出约定：所有以 "ALERT" 开头的行 = 需要人工介入的异常；无 ALERT = 账平。
-- =============================================================================

SET SESSION group_concat_max_len = 1000000;

SELECT '===== AQUAFLOW 日结对账开始 =====' AS banner, NOW() AS run_at;

-- -----------------------------------------------------------------------------
-- 等式1：押金账户余额 vs 押金流水
-- 需求：customer_deposit_account.balance 应等于 deposit_record 中该 (customer, station)
--       的金额净和。流水方向约定：正=客户预存/增加，负=扣减/退还。
--       （若业务把某类流水记为正数表示扣减，请在此调整符号口径。）
-- -----------------------------------------------------------------------------
SELECT '--- 等式1：押金账户余额 vs 流水净和 ---' AS section;

SELECT
  a.customer_id,
  a.station_id,
  a.balance                        AS account_balance,
  COALESCE(r.flow_sum, 0)          AS deposit_flow_sum,
  ROUND(a.balance - COALESCE(r.flow_sum, 0), 2) AS diff
FROM customer_deposit_account a
LEFT JOIN (
  SELECT customer_id, station_id, SUM(amount) AS flow_sum
  FROM deposit_record
  GROUP BY customer_id, station_id
) r ON r.customer_id = a.customer_id AND r.station_id = a.station_id
HAVING ABS(diff) > 0.009;

SELECT IF(COUNT(*) = 0, 'OK 等式1通过', CONCAT('ALERT 等式1不平：', COUNT(*), ' 个账户余额与流水不符')) AS result
FROM (
  SELECT a.customer_id
  FROM customer_deposit_account a
  LEFT JOIN (
    SELECT customer_id, station_id, SUM(amount) AS flow_sum
    FROM deposit_record GROUP BY customer_id, station_id
  ) r ON r.customer_id = a.customer_id AND r.station_id = a.station_id
  WHERE ABS(a.balance - COALESCE(r.flow_sum, 0)) > 0.009
) x;

-- -----------------------------------------------------------------------------
-- 等式2：订单支付状态 vs 支付流水
-- 规则：
--   payment_status=2(PAID)   → 必须存在 status=2 的支付流水
--   payment_status=3(REFUNDED)→ 必须存在 status=3 的退款流水
--   payment_status=0(UNPAID) → 不应存在 status=2 的流水（否则有款无状态）
-- -----------------------------------------------------------------------------
SELECT '--- 等式2：订单 payment_status vs payment_record ---' AS section;

-- 2a. 标记为已付款却无 PAID 流水（账上"有状态无凭证"）
SELECT o.id AS order_id, o.station_id, o.payment_status, o.payment_method, o.total_amount,
       'ALERT: 订单已付款但无 PAID 流水' AS issue
FROM orders o
WHERE o.payment_status = 2
  AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2);

-- 2b. 存在 PAID 流水但订单未标记已付款（账上"有凭证无状态"）
SELECT o.id AS order_id, o.station_id, o.payment_status, o.total_amount,
       'ALERT: 有 PAID 流水但订单未置已付款' AS issue
FROM orders o
WHERE o.payment_status <> 2
  AND EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2);

-- 2c. 孤儿支付流水（订单不存在）
SELECT p.id AS payment_id, p.order_id, p.amount, p.status,
       'ALERT: 孤儿支付流水（订单不存在）' AS issue
FROM payment_record p
LEFT JOIN orders o ON o.id = p.order_id
WHERE o.id IS NULL;

-- 2d. 订单标记已退款却无 REFUNDED 流水
SELECT o.id AS order_id, o.station_id, o.payment_status,
       'ALERT: 订单已退款但无 REFUNDED 流水' AS issue
FROM orders o
WHERE o.payment_status = 3
  AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 3);

-- -----------------------------------------------------------------------------
-- 等式3：桶三态守恒
-- 在手桶（customer_barrel_asset）+ 配送中桶（customer_barrel_in_transit 未结算）+ 欠桶
--   在手桶记录只应存在于 status 未结束的行；配送中若已 DELIVERED 说明未并入在手（重复计数）。
-- -----------------------------------------------------------------------------
SELECT '--- 等式3：桶三态守恒 ---' AS section;

-- 3a. 配送中表残留"已送达"记录（应已并入在手并删除，否则桶被重复计数）
SELECT customer_id, product_id, station_id, SUM(qty) AS stuck_qty,
       'ALERT: 配送中桶仍为 DELIVERED（未并入在手/未清理）' AS issue
FROM customer_barrel_in_transit
WHERE status = 'DELIVERED'
GROUP BY customer_id, product_id, station_id;

-- 3b. 在手桶出现负库存（异常）
SELECT customer_id, product_id, station_id, quantity,
       'ALERT: 在手桶数量为负' AS issue
FROM customer_barrel_asset
WHERE quantity < 0;

-- 3c. 占用越界（异常）：over < −权益 意味着"顾客手上有负数个桶"，物理上不可能。
--     注意口径：over < 0 本身【不是】异常 —— 那是"顾客多还桶、寄存在水站"，业务上合法。
--     旧版本这条查的是已停写的旧欠桶表 customer_owed_barrel（owed_qty < 0），
--     该表自桶权益模型上线后零读零写，查询恒返回空集，属失效断言。
SELECT o.customer_id, o.station_id, o.product_id, o.over_qty,
       'ALERT: 占用越界(over < -权益，占用为负)' AS issue
FROM customer_barrel_over o
WHERE o.over_qty < -(SELECT COALESCE(SUM(l.remain_qty), 0) FROM customer_barrel_lot l
                     WHERE l.customer_id = o.customer_id AND l.station_id = o.station_id
                       AND l.product_id = o.product_id AND l.status = 1);

-- 3d. 桶资产折算押金 vs 押金账户余额（宏观敞口，仅供参考，非硬阈值）
SELECT
  a.customer_id,
  a.station_id,
  SUM(a.quantity)                                   AS barrel_on_hand,
  SUM(a.quantity * COALESCE(p.deposit, 0))          AS barrel_deposit_value,
  COALESCE(da.balance, 0)                           AS deposit_account_balance,
  ROUND(SUM(a.quantity * COALESCE(p.deposit, 0)) - COALESCE(da.balance, 0), 2) AS gauge_diff
FROM customer_barrel_asset a
LEFT JOIN product p ON p.id = a.product_id
LEFT JOIN customer_deposit_account da
       ON da.customer_id = a.customer_id AND da.station_id = a.station_id
GROUP BY a.customer_id, a.station_id
HAVING ABS(gauge_diff) > 0.009;

-- -----------------------------------------------------------------------------
-- 等式4 [AQ-029]：inventory.quantity  ==  inventory_record 流水累计
-- 为库存建立勾稽对象后，两者应严格相等；不等说明有未记流水的库存变动（如直改库）。
-- -----------------------------------------------------------------------------
SELECT '--- 等式4：库存数量 vs 库存流水累计 ---' AS section;

SELECT
  i.station_id,
  i.product_id,
  i.quantity AS stock_qty,
  COALESCE(r.flow_sum, 0) AS flow_sum,
  (i.quantity - COALESCE(r.flow_sum, 0)) AS diff,
  'ALERT: 库存与流水累计不符' AS issue
FROM inventory i
LEFT JOIN (
  SELECT station_id, product_id, SUM(delta) AS flow_sum
  FROM inventory_record GROUP BY station_id, product_id
) r ON r.station_id = i.station_id AND r.product_id = i.product_id
HAVING diff <> 0;

-- -----------------------------------------------------------------------------
-- 汇总：任一 ALERT 存在即需人工介入
-- -----------------------------------------------------------------------------
SELECT '===== AQUAFLOW 日结对账结束 =====' AS banner, NOW() AS finished_at;
