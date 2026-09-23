-- =============================================================================
-- 桶权益模型 v3：为「物理桶守恒」对账补上流水落库所需的字段
--
-- 背景：对账 V2 的 E5（物理桶守恒）需要从流水重算「顾客手上实际有几个桶」：
--     占用 = Σ配送(送出 − 收回) − Σ纯还桶 − Σ退桶 − Σ丢失损坏
-- 但 barrel_record 里<b>根本没有记录每次配送送出/收回了几个桶</b>——
-- BarrelLedgerService.applyDelivery 只改了 over，没有留流水。
-- 没有流水，E5 就是空中楼阁，只能靠"信任代码没写错"。
--
-- 本脚本补两个列，并约定新增 type=8「配送收发明细」记录：
--   delivered_qty = 本单送出满桶数
--   returned_qty  = 本单收回空桶数
-- 两列只在 type=8 时有值，其他类型恒为 0（不参与守恒计算）。
--
-- 幂等：用 information_schema 判断后再 ALTER（MySQL 8.4 不支持 ADD COLUMN IF NOT EXISTS）
-- =============================================================================

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record'
      AND COLUMN_NAME = 'delivered_qty');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE barrel_record ADD COLUMN delivered_qty INT NOT NULL DEFAULT 0 COMMENT ''本单送出满桶数(仅type=8配送收发明细)''',
    'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record'
      AND COLUMN_NAME = 'returned_qty');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE barrel_record ADD COLUMN returned_qty INT NOT NULL DEFAULT 0 COMMENT ''本单收回空桶数(仅type=8配送收发明细)''',
    'SELECT 1');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 自检
SELECT COLUMN_NAME, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'barrel_record'
  AND COLUMN_NAME IN ('delivered_qty', 'returned_qty');
