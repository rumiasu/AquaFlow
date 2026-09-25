-- =============================================================================
-- V43: 楼层数改为「配送员选填上报」+ 楼层凭证照片
-- =============================================================================
-- ⚠️ 编号：实测 `ls sql/` 之后取 v43（v38 商品图片库 / v39 进货成本 / v40 客户特权 /
--    v41 删 orders.batch_id / v42 工资只留两项）。新建迁移前先看编号。
--
-- 执行方式（必须指定库；导入走字节级重定向）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v43_floor_report.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么加（2026-09-18 产品决定）
--
--   楼层补贴是给配送员的钱，而"爬了几层"只有他自己知道 —— 让客户在地址里填的楼层既当收费依据
--   又当发钱依据，等于用别人的话给自己发工资，虚报没有任何痕迹。所以：
--     · **配送员在完成配送时选填楼层数**（有的填、没有的不填），填了就以此为准；
--     · 没填就**沿用地址楼层**（不惩罚不填的人，也不多付）；
--     · 与地址不一致时在收益明细里**标记**，站长能看见；
--     · 照片**不强制**（配送员与站长都可上传），作为与客户对峙时的凭证。
--
--   两笔钱仍然是分开的（docs/design/16 §2.4）：
--     · `orders.floor_fee`   = 向客户收的楼层费（**下单时按地址快照**，本脚本一个字都不改）
--     · `staff_earning` 的 FLOOR_BONUS = 给配送员的补贴（按本列的上报值算）
--
-- 影响面
--   · `orders` 加 1 个可空列：存量订单全为 NULL = 没上报过 → 楼层补贴口径与升级前完全一致；
--   · `order_image.type` **只改注释**（新增取值 3 楼层凭证），不动任何数据；
--   · 不碰任何金额列、不碰对账等式（E-PAY 比的是结算单合计与明细之和）。
--
-- 幂等：可重复执行（列/注释都已是最新则 skip）。
-- 回滚：ALTER TABLE orders DROP COLUMN reported_floor;
--       ALTER TABLE order_image MODIFY COLUMN `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1正常送达 2异常';
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('orders','order_image'));
SET @s := IF(@t=2,
  "SELECT '开始执行 V43（配送员上报楼层 + 楼层凭证照片类型）' AS note",
  "SELECT 'ABORT: orders/order_image 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) orders.reported_floor（可空：NULL = 没上报，沿用地址楼层）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='reported_floor');
SET @s := IF(@has_col=0,
  'ALTER TABLE orders ADD COLUMN reported_floor int DEFAULT NULL COMMENT ''配送员上报的楼层（选填）。NULL=没上报，楼层补贴沿用地址楼层；与地址不一致时在收益明细里标记''',
  'SELECT ''skip: orders.reported_floor 已存在'' AS r');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) order_image.type 注释补上取值 3（只改注释，不动数据）
SET @s := 'ALTER TABLE order_image MODIFY COLUMN `type` tinyint NOT NULL DEFAULT ''1'' COMMENT ''类型：1正常送达 2异常 3楼层凭证''';
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 校验
SELECT '校验A：orders.reported_floor 应已存在且可空' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='reported_floor';

SELECT '校验B：order_image.type 注释应含 3楼层凭证' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='order_image' AND COLUMN_NAME='type';

SELECT '校验C：金额与流水未受影响' AS check_item;
SELECT (SELECT COUNT(*) FROM orders) AS orders_rows,
       (SELECT IFNULL(SUM(total_amount),0) FROM orders) AS orders_total_sum,
       (SELECT COUNT(*) FROM staff_earning) AS earning_rows;
