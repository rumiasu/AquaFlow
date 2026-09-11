-- =============================================================================
-- 桶权益模型 v1 —— S1 DDL（只加不改，幂等，可重复执行）
-- -----------------------------------------------------------------------------
-- 业务语义（已由业务方确认，不要凭直觉改）：
--   押金 = 购买「桶权益」（可退），桶是流动实体（工厂→水站→配送员→顾客→水站→工厂）
--   权益 Right  : 顾客在该站该桶型拥有的可占用桶数（不是"手里有几个桶"）
--   批次 Lot     : 一次购买一批权益，保存【买入当时单价快照】→ 金额唯一真相源
--   占用 Occupied: 顾客当前实际占有的实体桶数（派生，不落表）
--   over         : 占用 − 权益，按 (customer, station, product)，【可为负】
--
--   恒等式：occupied = right + over      （按 customer × station × product 各自成立）
--           right_amt = Σ lot.remain_qty × lot.unit_price
--
--   over > 0  欠桶（常规）
--   over = 0  正常
--   over < 0  顾客多还了桶 / 水站暂存的桶 —— 合法状态，
--             不是脏数据、不是负债、不是负权益。禁止任何 over>=0 形式的拦截校验。
--             over<0 不产生退款；唯一可退款的是 lot 里剩余的权益。
--
-- 与计划的 3 处偏差（均以线上真实结构为准，比计划更优）：
--   1) 不加 orders.right_purchase_qty：customer_barrel_in_transit.qty 本身就是
--      下单时算出的 shortage，且【自带 product 维度】；单值列反而丢维度。
--   2) 不加 barrel_record.refund_amount：该表已有 deposit_refund，直接复用，避免重复列。
--   3) 不用生成列 pending_key：MySQL 唯一索引无法对"数量求和"做约束，
--      生成列唯一键只能防重复提交。真正的并发超发由应用层
--      SELECT ... FOR UPDATE 行锁解决；本脚本只加 (related_order_id, product_id)
--      唯一键防重复建配送中记录。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 0) 备份（只建一次，重复执行不会覆盖）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bak_bkt_customer_barrel_asset     AS SELECT * FROM customer_barrel_asset;
CREATE TABLE IF NOT EXISTS bak_bkt_customer_owed_barrel      AS SELECT * FROM customer_owed_barrel;
CREATE TABLE IF NOT EXISTS bak_bkt_customer_barrel_in_transit AS SELECT * FROM customer_barrel_in_transit;
CREATE TABLE IF NOT EXISTS bak_bkt_barrel_record             AS SELECT * FROM barrel_record;
CREATE TABLE IF NOT EXISTS bak_bkt_deposit_record            AS SELECT * FROM deposit_record;

-- -----------------------------------------------------------------------------
-- 1) 权益批次 / 押金条（金额唯一真相源）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_barrel_lot (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  lot_no            VARCHAR(32)  NOT NULL COMMENT '押金条凭证号 DPyyyymmdd-000001',
  customer_id       BIGINT       NOT NULL,
  station_id        BIGINT       NOT NULL,
  product_id        BIGINT       NOT NULL,
  unit_price        DECIMAL(10,2) NOT NULL COMMENT '买入当时单价(快照): 2026年30元买的, 2027年退就退30元',
  qty               INT          NOT NULL COMMENT '本批购买权益数',
  remain_qty        INT          NOT NULL COMMENT '剩余未退权益数',
  source_type       TINYINT      NOT NULL DEFAULT 1 COMMENT '1订单购买 2历史迁移 3人工补录',
  price_source      TINYINT      NOT NULL DEFAULT 1 COMMENT '1订单实付 2当时商品押金 3当前商品押金(兜底推断)',
  related_order_id  BIGINT       NULL,
  deposit_record_id BIGINT       NULL,
  status            TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 2已退完 3作废',
  is_migrated       TINYINT      NOT NULL DEFAULT 0 COMMENT '1=历史迁移批次(单价为推断, 退款需二次确认)',
  operator_id       BIGINT       NULL,
  note              VARCHAR(200) NULL,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_lot_no(lot_no),
  KEY idx_lot_csp(customer_id, station_id, product_id, status),
  KEY idx_lot_order(related_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桶权益批次(押金条): 金额唯一真相源';

-- -----------------------------------------------------------------------------
-- 2) 欠桶 / 过占（按商品，可为负）—— 取代 customer_owed_barrel
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_barrel_over (
  id           BIGINT   AUTO_INCREMENT PRIMARY KEY,
  customer_id  BIGINT   NOT NULL,
  station_id   BIGINT   NOT NULL,
  product_id   BIGINT   NOT NULL,
  over_qty     INT      NOT NULL DEFAULT 0
               COMMENT '过占=占用-权益; 正数=欠桶, 负数=多还桶(水站暂存), 均为合法状态',
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_over(customer_id, station_id, product_id),
  KEY idx_over_station(station_id),
  KEY idx_over_cs(customer_id, station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户过占桶(按商品, 可负, A水多还不能抵B水欠)';

-- -----------------------------------------------------------------------------
-- 3) 退桶(终止权益)的批次核销明细 —— 纯还桶不写本表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS barrel_record_lot (
  id         BIGINT        AUTO_INCREMENT PRIMARY KEY,
  record_id  BIGINT        NOT NULL COMMENT 'barrel_record.id',
  lot_id     BIGINT        NOT NULL COMMENT 'customer_barrel_lot.id',
  qty        INT           NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL COMMENT '核销时的批次单价快照',
  amount     DECIMAL(10,2) NOT NULL COMMENT 'qty × unit_price',
  UNIQUE KEY uk_rl(record_id, lot_id),
  KEY idx_rl_lot(lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退桶的权益批次核销明细; 纯还桶不写本表';

-- -----------------------------------------------------------------------------
-- 4) 加列（MySQL 8.4 无 ADD COLUMN IF NOT EXISTS，统一走 information_schema 判定）
-- -----------------------------------------------------------------------------

-- 4.1 customer_barrel_asset.right_amount 可退金额 = Σ remain_qty × unit_price
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_asset' AND COLUMN_NAME='right_amount');
SET @s := IF(@n=0,
  "ALTER TABLE customer_barrel_asset ADD COLUMN right_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '可退桶款=Σ lot.remain_qty×unit_price' AFTER quantity",
  "SELECT 'skip right_amount' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4.2 deposit_record: product_id / unit_price / quantity（支付时留痕，供 S2 回填 lot 推断单价）
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record' AND COLUMN_NAME='product_id');
SET @s := IF(@n=0,
  "ALTER TABLE deposit_record ADD COLUMN product_id BIGINT NULL COMMENT '桶权益对应商品(按商品隔离)' AFTER station_id",
  "SELECT 'skip deposit_record.product_id' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record' AND COLUMN_NAME='unit_price');
SET @s := IF(@n=0,
  "ALTER TABLE deposit_record ADD COLUMN unit_price DECIMAL(10,2) NULL COMMENT '桶权益买入单价快照' AFTER amount",
  "SELECT 'skip deposit_record.unit_price' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='deposit_record' AND COLUMN_NAME='quantity');
SET @s := IF(@n=0,
  "ALTER TABLE deposit_record ADD COLUMN quantity INT NULL COMMENT '本次涉及桶数' AFTER unit_price",
  "SELECT 'skip deposit_record.quantity' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4.3 customer_barrel_in_transit.unit_price（下单时快照 product.deposit，配送完成时转为 lot 单价）
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_in_transit' AND COLUMN_NAME='unit_price');
SET @s := IF(@n=0,
  "ALTER TABLE customer_barrel_in_transit ADD COLUMN unit_price DECIMAL(10,2) NULL COMMENT '下单时桶权益单价快照(转为lot.unit_price)' AFTER qty",
  "SELECT 'skip in_transit.unit_price' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4.4 barrel_record: 幂等 token / 确认人 / over 前后快照（over 可为负）
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='client_token');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD COLUMN client_token VARCHAR(64) NULL COMMENT '客户端幂等token(纯还桶/退桶防重复提交)' AFTER handle_note",
  "SELECT 'skip barrel_record.client_token' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='confirmed_by');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD COLUMN confirmed_by BIGINT NULL COMMENT '确认收到空桶的操作人(DEF-7)' AFTER client_token",
  "SELECT 'skip barrel_record.confirmed_by' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='confirmed_time');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD COLUMN confirmed_time DATETIME NULL COMMENT '确认收到空桶时间' AFTER confirmed_by",
  "SELECT 'skip barrel_record.confirmed_time' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='over_before');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD COLUMN over_before INT NULL COMMENT '变更前over(可负)' AFTER confirmed_time",
  "SELECT 'skip barrel_record.over_before' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND COLUMN_NAME='over_after');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD COLUMN over_after INT NULL COMMENT '变更后over(可负)' AFTER over_before",
  "SELECT 'skip barrel_record.over_after' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 5) 加索引（同样走 information_schema 判定）
-- -----------------------------------------------------------------------------

-- 5.1 配送中表：同一订单同一商品只能有一条配送中记录（防重复建记录；并发超发另由行锁解决）
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_barrel_in_transit' AND INDEX_NAME='uk_transit_order_product');
SET @s := IF(@n=0,
  "ALTER TABLE customer_barrel_in_transit ADD UNIQUE KEY uk_transit_order_product(related_order_id, product_id)",
  "SELECT 'skip uk_transit_order_product' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5.2 barrel_record 幂等键（NULL 不参与唯一，历史数据不受影响）
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='barrel_record' AND INDEX_NAME='uk_record_client_token');
SET @s := IF(@n=0,
  "ALTER TABLE barrel_record ADD UNIQUE KEY uk_record_client_token(client_token)",
  "SELECT 'skip uk_record_client_token' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 7) 术语统一：「在途」→「配送中」
--    全系统对外表述统一为「配送中」（= 已付款买下桶权益、但桶还没送到顾客手上）。
--    类名 CustomerBarrelInTransit / 表名 customer_barrel_in_transit 沿用历史命名，
--    仅为避免改表成本，不代表对外术语 —— 注释、日志、接口文案一律写「配送中」。
-- -----------------------------------------------------------------------------
ALTER TABLE customer_barrel_in_transit
  COMMENT = '客户配送中桶: 已付款买下桶权益但尚未送达; 送达后转为押金条(lot)并计入权益。表名沿用历史命名 in_transit, 对外术语一律称「配送中」';

ALTER TABLE customer_barrel_in_transit
  MODIFY COLUMN qty INT NOT NULL DEFAULT 0 COMMENT '配送中桶数 = 本单新购权益数(下单时算出的 shortage)';

ALTER TABLE customer_barrel_in_transit
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
  COMMENT 'PENDING=配送中(已购待送) / DELIVERED=已送达(权益已转押金条, 记录保留可追溯) / CANCELLED=已取消';

-- -----------------------------------------------------------------------------
-- 6) 校验（重复执行结果应完全一致）
-- -----------------------------------------------------------------------------
SELECT '=== 新表 ===' AS t;
SELECT TABLE_NAME, TABLE_ROWS, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA=@db
  AND TABLE_NAME IN ('customer_barrel_lot','customer_barrel_over','barrel_record_lot');

SELECT '=== 新列 ===' AS t;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=@db
  AND ((TABLE_NAME='customer_barrel_asset'      AND COLUMN_NAME='right_amount')
    OR (TABLE_NAME='deposit_record'             AND COLUMN_NAME IN ('product_id','unit_price','quantity'))
    OR (TABLE_NAME='customer_barrel_in_transit' AND COLUMN_NAME='unit_price')
    OR (TABLE_NAME='barrel_record'              AND COLUMN_NAME IN ('client_token','confirmed_by','confirmed_time','over_before','over_after')))
ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT '=== 新索引 ===' AS t;
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=@db
  AND INDEX_NAME IN ('uk_transit_order_product','uk_record_client_token','uk_lot_no','uk_over','uk_rl')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE;

SELECT 'S1 DDL 完成' AS result;
