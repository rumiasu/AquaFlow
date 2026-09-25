-- =============================================================================
-- V34: 配送计费与配送范围的前置字段（Phase 0）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v34_delivery_fee_and_floors.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 这一版**只加字段，不改任何业务逻辑**（见 docs/design/16 §3.0 Phase 0）
--
--   目的：把"只能加不能改"的东西一次性加齐，且加完对现网零影响。
--   加的四组列：
--     1) station.lat / station.lng          —— 配送范围算距离要有站点坐标（原表只有 address 有坐标）
--     2) address.floor / has_elevator       —— 楼层费要有楼层与有无电梯
--     3) orders.delivery_fee / floor_fee    —— 费用列，**全部默认 0 且本版不参与任何计算**
--     4) payment_record.delivery_fee / floor_fee —— 同上，供支付流水与对账等式2对齐口径
--
--   ⚠️ 三条硬约束（务必遵守，否则会丢钱）：
--     · **费用绝不塞进 `water_amount`** —— 污染水费口径，水费与退款、报表、对账都相关。
--     · **费用绝不塞进 `deposit_amount`** —— 那是**可退押金**，退款路径按它释放押金余额
--       （PaymentServiceImpl 的 refundOrder 分支），把运费混进去会导致取消订单时**多退钱**。
--     · `has_elevator` **必须允许 NULL 并与 0 区分**：NULL = 未确认，0 = 确认无电梯。
--       把 NULL 当"无电梯"会向客户乱收费；当"有电梯"会漏收。收费判定见 docs/design/17 §4.4。
--
--   本版**不动** `orders.total_amount` 的构成（现为 水费 + 押金）。把费用并入总额是
--   Phase 1 的事，届时要同时改 PaymentServiceImpl.quote 与 OrderServiceImpl.createOrder，
--   两处必须调同一个计算函数 —— 该文件头注释记录过"计价双轨"引发的客诉事故。
--
--   坐标精度取 decimal(10,6)，与 address 表一致（约 0.11 米，足够）。
--
-- 影响面
--   · 纯新增 8 个列（4 张表 × 2 列），**不改任何既有列的类型与含义、不动任何存量数据**；
--   · 存量行的费用列自动为 0、坐标为 NULL、楼层为 NULL —— 与现状语义一致（当前既没有
--     配送费也没有楼层费），前端零感知；
--   · 代码侧：entity 加字段、mapper 的 insert 带上新列；`station` 的通用 update **刻意不带**
--     坐标（只新增 updateCoordinates），理由与 updateOperatingStatus 同源 ——
--     通用 update 是整行覆盖，站长只改营业状态却把坐标/站名写没的事故本仓已发生过多次。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS）。
-- 回滚：ALTER TABLE orders DROP COLUMN floor_fee, DROP COLUMN delivery_fee;
--       ALTER TABLE payment_record DROP COLUMN floor_fee, DROP COLUMN delivery_fee;
--       ALTER TABLE address DROP COLUMN has_elevator, DROP COLUMN floor;
--       ALTER TABLE station DROP COLUMN lng, DROP COLUMN lat;
--       （回滚只丢"配送计费的前置字段"，不影响订单金额、桶账、水票、押金任何余额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库：确认四张表都在
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('station','address','orders','payment_record'));
SET @s := IF(@t=4,
  "SELECT '开始执行 V34（配送计费前置字段）' AS note",
  "SELECT 'ABORT: station/address/orders/payment_record 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) station.lat / station.lng
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station' AND COLUMN_NAME='lat')=0,
  "ALTER TABLE station ADD COLUMN lat decimal(10,6) NULL DEFAULT NULL COMMENT '纬度（站长地图选点；NULL=未设置，配送范围校验会跳过）' AFTER address",
  "SELECT 'skip: station.lat 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station' AND COLUMN_NAME='lng')=0,
  "ALTER TABLE station ADD COLUMN lng decimal(10,6) NULL DEFAULT NULL COMMENT '经度（站长地图选点；NULL=未设置，配送范围校验会跳过）' AFTER lat",
  "SELECT 'skip: station.lng 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) address.floor / address.has_elevator
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='address' AND COLUMN_NAME='floor')=0,
  "ALTER TABLE address ADD COLUMN floor int NULL DEFAULT NULL COMMENT '楼层（楼层费依据；NULL=未填，不收楼层费只提示）' AFTER lng",
  "SELECT 'skip: address.floor 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='address' AND COLUMN_NAME='has_elevator')=0,
  "ALTER TABLE address ADD COLUMN has_elevator tinyint NULL DEFAULT NULL COMMENT '有无电梯: NULL=未确认(不收楼层费) 0=无电梯 1=有电梯' AFTER floor",
  "SELECT 'skip: address.has_elevator 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) orders.delivery_fee / orders.floor_fee
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='delivery_fee')=0,
  "ALTER TABLE orders ADD COLUMN delivery_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '配送费（v34 起有列；并入 total_amount 是 Phase 1 的事）' AFTER deposit_amount",
  "SELECT 'skip: orders.delivery_fee 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='floor_fee')=0,
  "ALTER TABLE orders ADD COLUMN floor_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '楼层费（向客户收的那一笔，与给配送员的楼层补贴是两笔钱）' AFTER delivery_fee",
  "SELECT 'skip: orders.floor_fee 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) payment_record.delivery_fee / payment_record.floor_fee
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND COLUMN_NAME='delivery_fee')=0,
  "ALTER TABLE payment_record ADD COLUMN delivery_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '配送费（与 orders.delivery_fee 对齐口径，供对账等式2）' AFTER barrel_deposit",
  "SELECT 'skip: payment_record.delivery_fee 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND COLUMN_NAME='floor_fee')=0,
  "ALTER TABLE payment_record ADD COLUMN floor_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '楼层费（与 orders.floor_fee 对齐口径）' AFTER delivery_fee",
  "SELECT 'skip: payment_record.floor_fee 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5) 校验
SELECT 'V34 完成：配送计费前置字段已就绪' AS note;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db
   AND ((TABLE_NAME='station'  AND COLUMN_NAME IN ('lat','lng'))
     OR (TABLE_NAME='address'  AND COLUMN_NAME IN ('floor','has_elevator'))
     OR (TABLE_NAME='orders'   AND COLUMN_NAME IN ('delivery_fee','floor_fee'))
     OR (TABLE_NAME='payment_record' AND COLUMN_NAME IN ('delivery_fee','floor_fee')))
 ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT '校验B：存量行费用列必须全为 0（有非 0 说明本不该动数据的地方被动了）' AS check_item;
SELECT (SELECT COUNT(*) FROM orders WHERE delivery_fee <> 0 OR floor_fee <> 0) AS orders_nonzero_fee,
       (SELECT COUNT(*) FROM payment_record WHERE delivery_fee <> 0 OR floor_fee <> 0) AS payments_nonzero_fee;

SELECT '校验C：存量金额列未受影响' AS check_item;
SELECT (SELECT COUNT(*) FROM orders) AS orders_rows,
       (SELECT IFNULL(SUM(total_amount),0) FROM orders) AS orders_total_sum,
       (SELECT IFNULL(SUM(water_amount),0) FROM orders) AS orders_water_sum,
       (SELECT IFNULL(SUM(deposit_amount),0) FROM orders) AS orders_deposit_sum;

SELECT '校验D：楼层与坐标分布（应全部为 NULL，即未填）' AS check_item;
SELECT (SELECT COUNT(*) FROM station WHERE lat IS NOT NULL OR lng IS NOT NULL) AS station_with_coords,
       (SELECT COUNT(*) FROM address WHERE floor IS NOT NULL OR has_elevator IS NOT NULL) AS address_with_floor;
