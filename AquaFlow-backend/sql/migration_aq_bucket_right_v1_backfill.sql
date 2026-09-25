-- =============================================================================
-- 桶权益模型 v1 —— S2 历史数据回填（幂等，可重复执行）
-- 前置：先执行 migration_aq_bucket_right_v1_ddl.sql
-- -----------------------------------------------------------------------------
-- 原则：
--   1) 数量以 customer_barrel_asset 为准（不改现有系统口径），差额只登记不强行对齐；
--   2) 单价无历史价格表，只能用「当前 product.deposit」+ 订单押金交叉校验，
--      统一标记 is_migrated=1，退桶时必须二次提示；
--   3) over 允许回填出【负数】（多还桶/水站暂存），这是合法状态，不修正；
--   4) 回填后只做只读校验（E3/E4/E6），不阻断业务。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 0) 差异登记表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS migration_diff_bucket_right (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT, station_id BIGINT, product_id BIGINT,
  kind        VARCHAR(48)  NOT NULL COMMENT 'LOT_VS_DEPOSIT | OVER_VS_OLD_OWED | RIGHT_AMT_EXCEED_BALANCE',
  expected_val DECIMAL(12,2), actual_val DECIMAL(12,2), diff_val DECIMAL(12,2),
  note        VARCHAR(500),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_diff_kind(kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桶权益迁移差异登记(人工核对用, 不强行对齐)';

-- -----------------------------------------------------------------------------
-- 1) 清掉上一轮回填结果（保证幂等；只删迁移批次，不碰后续业务写入的 lot）
-- -----------------------------------------------------------------------------
DELETE FROM barrel_record_lot WHERE lot_id IN (SELECT id FROM customer_barrel_lot WHERE is_migrated=1);
DELETE FROM customer_barrel_lot   WHERE is_migrated=1;
DELETE FROM customer_barrel_over;
DELETE FROM migration_diff_bucket_right;

-- -----------------------------------------------------------------------------
-- 2) Lot 回填：每个 (customer, station, product) 一个迁移批次
--    数量  = customer_barrel_asset.quantity
--    单价  = product.deposit（无历史价格表，只能如此）
--    price_source: 2=有已付款订单押金可交叉印证  3=纯当前押金价兜底(偏差风险)
-- -----------------------------------------------------------------------------
INSERT INTO customer_barrel_lot
 (lot_no, customer_id, station_id, product_id,
  unit_price, qty, remain_qty,
  source_type, price_source, related_order_id,
  status, is_migrated, note, create_time, update_time)
SELECT
  CONCAT('MG', DATE_FORMAT(NOW(), '%Y%m%d'), '-', LPAD(a.id, 6, '0')),
  a.customer_id, a.station_id, a.product_id,
  COALESCE(p.deposit, 0),
  a.quantity, a.quantity,
  2,
  CASE WHEN ref.ref_order_id IS NOT NULL THEN 2 ELSE 3 END,
  ref.ref_order_id,
  1, 1,
  CONCAT('历史迁移: 数量对齐 customer_barrel_asset.id=', a.id,
         CASE WHEN ref.ref_order_id IS NOT NULL
              THEN CONCAT('; 有已付押金订单#', ref.ref_order_id, ' 金额', ref.ref_deposit, ' 可交叉印证')
              ELSE '; 无已付押金订单可印证, 单价为当前商品押金价兜底' END),
  NOW(), NOW()
FROM customer_barrel_asset a
LEFT JOIN product p ON p.id = a.product_id
LEFT JOIN (
  SELECT o.customer_id, o.station_id, oi.product_id,
         MAX(o.id) AS ref_order_id, MAX(o.deposit_amount) AS ref_deposit
  FROM orders o
  JOIN order_item oi ON oi.order_id = o.id
  WHERE o.payment_status = 2 AND o.deposit_amount > 0
  GROUP BY o.customer_id, o.station_id, oi.product_id
) ref ON ref.customer_id = a.customer_id
     AND ref.station_id  = a.station_id
     AND ref.product_id  = a.product_id
WHERE a.quantity > 0;

-- -----------------------------------------------------------------------------
-- 3) 占用回填：只统计【已送达(3)/已完成(4)】订单的 (送出 − 收回)
--    多商品单按 order_item.quantity 比例分摊
-- -----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_occupied;
CREATE TEMPORARY TABLE tmp_occupied AS
SELECT o.customer_id, o.station_id, oi.product_id,
       SUM(ROUND((o.delivery_bucket_qty - COALESCE(o.return_bucket_qty, 0))
                 * oi.quantity / NULLIF(oi2.total_qty, 0))) AS occupied
FROM orders o
JOIN order_item oi ON oi.order_id = o.id
JOIN (SELECT order_id, SUM(quantity) AS total_qty FROM order_item GROUP BY order_id) oi2
     ON oi2.order_id = o.id
WHERE o.status IN (3, 4)
  AND o.delivery_bucket_qty IS NOT NULL
GROUP BY o.customer_id, o.station_id, oi.product_id;

-- -----------------------------------------------------------------------------
-- 4) over 回填：over = 占用 − 权益（允许负数 = 多还桶/水站暂存，合法）
-- -----------------------------------------------------------------------------
INSERT INTO customer_barrel_over (customer_id, station_id, product_id, over_qty, create_time, update_time)
SELECT r.customer_id, r.station_id, r.product_id,
       COALESCE(o.occupied, 0) - r.right_qty AS over_qty,
       NOW(), NOW()
FROM (
  SELECT customer_id, station_id, product_id, SUM(remain_qty) AS right_qty
  FROM customer_barrel_lot WHERE status = 1
  GROUP BY customer_id, station_id, product_id
) r
LEFT JOIN tmp_occupied o
       ON o.customer_id = r.customer_id AND o.station_id = r.station_id AND o.product_id = r.product_id
WHERE COALESCE(o.occupied, 0) - r.right_qty <> 0;

-- 顾客有占用但没有任何权益记录（历史上权益没进 asset 的漏账）也要登记
INSERT INTO customer_barrel_over (customer_id, station_id, product_id, over_qty, create_time, update_time)
SELECT o.customer_id, o.station_id, o.product_id, o.occupied, NOW(), NOW()
FROM tmp_occupied o
LEFT JOIN customer_barrel_lot l
       ON l.customer_id = o.customer_id AND l.station_id = o.station_id
      AND l.product_id = o.product_id AND l.status = 1
WHERE l.id IS NULL AND o.occupied <> 0;

-- -----------------------------------------------------------------------------
-- 5) right_amount 回填：可退桶款 = Σ remain_qty × unit_price
-- -----------------------------------------------------------------------------
UPDATE customer_barrel_asset a
JOIN (
  SELECT customer_id, station_id, product_id, SUM(remain_qty * unit_price) AS amt
  FROM customer_barrel_lot WHERE status = 1
  GROUP BY customer_id, station_id, product_id
) t ON t.customer_id = a.customer_id AND t.station_id = a.station_id AND t.product_id = a.product_id
SET a.right_amount = t.amt, a.update_time = NOW();

UPDATE customer_barrel_asset SET right_amount = 0
WHERE right_amount IS NULL;

-- -----------------------------------------------------------------------------
-- 6) 差异登记（只登记，不强行对齐）
-- -----------------------------------------------------------------------------

-- 6.1 旧欠桶表 vs 新 over（站点级 vs 商品级，本身不可完全对齐，仅供人工参考）
INSERT INTO migration_diff_bucket_right (customer_id, station_id, product_id, kind, expected_val, actual_val, diff_val, note)
SELECT ow.customer_id, ow.station_id, NULL,
       'OVER_VS_OLD_OWED', ow.owed_qty, COALESCE(s.over_sum, 0),
       COALESCE(s.over_sum, 0) - ow.owed_qty,
       '旧 customer_owed_barrel 为站点级(不分商品), 新 over 为商品级; 差额需人工核对'
FROM customer_owed_barrel ow
LEFT JOIN (
  SELECT customer_id, station_id, SUM(over_qty) AS over_sum
  FROM customer_barrel_over GROUP BY customer_id, station_id
) s ON s.customer_id = ow.customer_id AND s.station_id = ow.station_id
WHERE ow.owed_qty <> 0 OR COALESCE(s.over_sum, 0) <> 0;

-- 6.2 权益金额 > 押金账户余额（退款会穿底，必须人工处理）
INSERT INTO migration_diff_bucket_right (customer_id, station_id, product_id, kind, expected_val, actual_val, diff_val, note)
SELECT a.customer_id, a.station_id, NULL,
       'RIGHT_AMT_EXCEED_BALANCE', COALESCE(acc.balance, 0), a.right_amt,
       a.right_amt - COALESCE(acc.balance, 0),
       '权益可退金额超过押金账户余额: 说明 asset.quantity 或历史押金入账有问题, 退桶前必须人工核对'
FROM (
  SELECT customer_id, station_id, SUM(right_amount) AS right_amt
  FROM customer_barrel_asset GROUP BY customer_id, station_id
) a
LEFT JOIN customer_deposit_account acc
       ON acc.customer_id = a.customer_id AND acc.station_id = a.station_id
WHERE a.right_amt > COALESCE(acc.balance, 0);

-- -----------------------------------------------------------------------------
-- 7) 只读校验
-- -----------------------------------------------------------------------------
SELECT '=== E3 权益数量/金额 vs lot ===' AS t;
SELECT a.customer_id, a.station_id, a.product_id,
       a.quantity AS asset_qty, COALESCE(l.lot_qty, 0) AS lot_qty,
       a.right_amount, COALESCE(l.lot_amt, 0) AS lot_amt,
       a.quantity - COALESCE(l.lot_qty, 0) AS qty_diff,
       ROUND(a.right_amount - COALESCE(l.lot_amt, 0), 2) AS amt_diff
FROM customer_barrel_asset a
LEFT JOIN (
  SELECT customer_id, station_id, product_id,
         SUM(remain_qty) AS lot_qty, SUM(remain_qty * unit_price) AS lot_amt
  FROM customer_barrel_lot WHERE status = 1 GROUP BY customer_id, station_id, product_id
) l ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id;

SELECT '=== E4 over 与 权益(负数合法, 但不得小于 -权益) ===' AS t;
SELECT o.customer_id, o.station_id, o.product_id, o.over_qty,
       (SELECT SUM(remain_qty) FROM customer_barrel_lot l
         WHERE l.customer_id=o.customer_id AND l.station_id=o.station_id
           AND l.product_id=o.product_id AND l.status=1) AS right_qty,
       CASE WHEN o.over_qty < -(SELECT SUM(remain_qty) FROM customer_barrel_lot l
                                 WHERE l.customer_id=o.customer_id AND l.station_id=o.station_id
                                   AND l.product_id=o.product_id AND l.status=1)
            THEN '数据损坏: over < -权益' ELSE 'OK' END AS chk
FROM customer_barrel_over o;

SELECT '=== E6 押金余额 vs 权益金额(告警) ===' AS t;
SELECT a.customer_id, a.station_id, SUM(a.right_amount) AS right_amt,
       COALESCE(acc.balance, 0) AS balance,
       CASE WHEN SUM(a.right_amount) > COALESCE(acc.balance, 0) THEN '穿底风险' ELSE 'OK' END AS chk
FROM customer_barrel_asset a
LEFT JOIN customer_deposit_account acc
       ON acc.customer_id = a.customer_id AND acc.station_id = a.station_id
GROUP BY a.customer_id, a.station_id, acc.balance;

SELECT '=== 待人工核对的差异 ===' AS t;
SELECT kind, COUNT(*) AS cnt FROM migration_diff_bucket_right GROUP BY kind;

SELECT '=== 回填结果 ===' AS t;
SELECT (SELECT COUNT(*) FROM customer_barrel_lot)  AS lots,
       (SELECT COUNT(*) FROM customer_barrel_over) AS overs,
       (SELECT COUNT(*) FROM migration_diff_bucket_right) AS diffs;

SELECT 'S2 回填完成' AS result;
