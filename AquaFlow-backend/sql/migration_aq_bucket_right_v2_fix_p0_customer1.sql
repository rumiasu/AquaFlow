-- =============================================================================
-- P0 校准：顾客 1 押金穿底（方案 A —— 以付款凭证为准）
--
-- 问题：权益可退金额 ¥560（p1 4×60 + p8 8×40），但押金账户余额只有 ¥140。
--
-- 根因（已查证，不是推测）：
--   1. 顾客 1 全库只有一笔订单 #1：deposit_amount=140.00、payment_status=2(已付)。
--      按当时单价 p1=¥60、p8=¥40，140 = 1×60 + 2×40 → 真实权益是 p1=1、p8=2。
--   2. 订单明细 order_item(id=1,2) = p1×1、p8×2，与上一条吻合。
--   3. barrel_record / customer_barrel_in_transit 对顾客 1 全空
--      → 付款之后没有任何配送、还桶、退桶流水，占用不可能超过 3。
--   4. S2 回填时把 customer_barrel_asset.quantity(=4/8，历史脏数据) 当基准建了 lot，
--      再把「占用(1/2) − 权益(4/8)」的差额塞进 over(=-3/-6)。
--      即：占用(=3)一直是对的，错的只是被放大的权益。
--
-- 处理（方案 A）：把权益校准到付款凭证对应的 1 / 2，over 归零。
--   改前：权益 12、占用 3（= 12 − 9）、可退 ¥560
--   改后：权益  3、占用 3（=  3 + 0）、可退 ¥140 = 押金账户余额 ¥140  ✅
--   物理占用不变，顾客「手里有几个桶」没有任何变化。
--
-- 幂等：所有 UPDATE 都写成目标定值，重复执行结果一致；差异记录用 NOT EXISTS 防重。
-- =============================================================================

-- 0) 差异表加「已处理」标记列（幂等；MySQL 8.4 不支持 ADD COLUMN IF NOT EXISTS）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'migration_diff_bucket_right'
      AND COLUMN_NAME = 'handled');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE migration_diff_bucket_right ADD COLUMN handled TINYINT NOT NULL DEFAULT 0 COMMENT ''0=待处理 1=已人工处理''',
    'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'migration_diff_bucket_right'
      AND COLUMN_NAME = 'handled_note');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE migration_diff_bucket_right ADD COLUMN handled_note VARCHAR(500) NULL COMMENT ''人工处理说明''',
    'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 登记本次校准（只插一次）
INSERT INTO migration_diff_bucket_right
    (customer_id, station_id, product_id, kind, expected_val, actual_val, diff_val,
     note, create_time, handled, handled_note)
SELECT 1, 1, NULL, 'RIGHT_FIX_APPLIED', 140.00, 560.00, 420.00,
       'P0校准(方案A): 唯一付款凭证为订单#1 押金140.00 = 1×60(p1) + 2×40(p8)，但权益被回填成 p1=4/p8=8。已按付款凭证把权益校准为 p1=1/p8=2 并让 over 归零，物理占用(3个)不变。校准前: 权益12/可退560.00/over=-3,-6',
       NOW(), 1, '方案A: 以付款凭证为准（未查到线下收款凭证）'
WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM migration_diff_bucket_right
                                 WHERE customer_id = 1 AND kind = 'RIGHT_FIX_APPLIED') t);

-- 2) 权益批次 lot：数量与剩余量都校准到付款凭证（p1=1、p8=2）
UPDATE customer_barrel_lot
   SET qty = 1, remain_qty = 1,
       note = CONCAT(IFNULL(note,''), ' | P0校准: 按订单#1付款凭证 4→1')
 WHERE customer_id = 1 AND station_id = 1 AND product_id = 1 AND qty = 4 AND remain_qty = 4;

UPDATE customer_barrel_lot
   SET qty = 2, remain_qty = 2,
       note = CONCAT(IFNULL(note,''), ' | P0校准: 按订单#1付款凭证 8→2')
 WHERE customer_id = 1 AND station_id = 1 AND product_id = 8 AND qty = 8 AND remain_qty = 8;

-- 3) 权益汇总 asset：数量与派生金额同步（p1: 1×60=60，p8: 2×40=80）
UPDATE customer_barrel_asset
   SET quantity = 1, right_amount = 60.00, update_time = NOW()
 WHERE customer_id = 1 AND station_id = 1 AND product_id = 1;

UPDATE customer_barrel_asset
   SET quantity = 2, right_amount = 80.00, update_time = NOW()
 WHERE customer_id = 1 AND station_id = 1 AND product_id = 8;

-- 4) over 归零（占用不变：权益 12 − 9 = 3  →  权益 3 + 0 = 3）
UPDATE customer_barrel_over
   SET over_qty = 0, update_time = NOW()
 WHERE customer_id = 1 AND station_id = 1 AND product_id IN (1, 8);

-- 5) 关闭旧的穿底告警
UPDATE migration_diff_bucket_right
   SET handled = 1,
       handled_note = CONCAT(IFNULL(handled_note,''), '已按方案A校准，见 RIGHT_FIX_APPLIED 记录')
 WHERE customer_id = 1 AND kind = 'RIGHT_AMT_EXCEED_BALANCE' AND handled = 0;

-- 6) 自检（应输出：权益3 / 占用3 / 可退140.00 / 余额140.00）
SELECT '权益' k, SUM(remain_qty) v FROM customer_barrel_lot WHERE customer_id = 1
UNION ALL SELECT '占用', SUM(remain_qty) + (SELECT IFNULL(SUM(over_qty),0) FROM customer_barrel_over WHERE customer_id = 1)
         FROM customer_barrel_lot WHERE customer_id = 1
UNION ALL SELECT '可退款', SUM(remain_qty * unit_price) FROM customer_barrel_lot WHERE customer_id = 1
UNION ALL SELECT '账户余额', balance FROM customer_deposit_account WHERE customer_id = 1 AND station_id = 1;
