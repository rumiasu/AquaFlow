-- =============================================================================
-- V47: 订单「结算站」显式化（orders 加 settle_station_id + 存量回填）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   mysql -uroot --default-character-set=utf8mb4 <库名> < migration_v47_order_settle_station.sql
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-18 产品裁定）
--
--   产品原话：「配送费问题要改，**还有水费也一起给实际配送站**；押金问题特别提醒站长，
--   一般建议禁止外派直接拒单，因为押金不好划定……单独列分区会不会更便于管理，
--   毛利报表之类的也要**本站化**，毕竟定价啥的站内规矩可能不同。」
--
--   裁定之前，「这一单的营收归谁」在代码里**从来没有一处显式表达**，而是每个查询各自
--   推导一遍：看板/客户画像用 coalesce(delivery_station_id, station_id)（= 履约站口径），
--   毛利表与应收账款却用 station_id（= 归属站口径）。于是同一个"钱"在两个页面里归两个站，
--   跨站外派单必然对不上账 —— 而**两种写法都能编译、都不报错**，只能靠人记住哪条 SQL 是哪套。
--
--   本迁移把这件事变成**一列**：`orders.settle_station_id`（结算站 = 本单营收归谁）。
--
-- -----------------------------------------------------------------------------
-- 语义（务必按这个口径读）
--   · `station_id`          = **归属站**（客户主动选定的站，**定价方**；`DeliveryFeeUtil` 按它算费）
--   · `delivery_station_id` = **履约站**（谁去送：库存扣减、配送员清单、工钱）
--   · `settle_station_id`   = **结算站**（水费 + 配送费 + 楼层费归它）← 本列
--   · 取值规则（写入点见下面的"影响面"）：
--       下单时                = station_id
--       抢单 / 定向外派成功后  = 履约站（目标站）
--       取消外派 / 召回 / 退回池 / 指定退回-同意 = 回 station_id
--   · **押金、水票、桶权益仍按归属站**（`customer_deposit_account` / `ticket_*` /
--     `customer_barrel_*`）—— 那是"客户买在哪个站的资产"，与"这单营收归谁"是两件事，
--     本列**不影响**它们，一行都不动。
--   · 读取一律用 `coalesce(settle_station_id, delivery_station_id, station_id)`：
--     ⚠️ **这是防御，不是常态** —— 允许"漏写 settle 的历史/未来行"不丢营收（旧行为 =
--     coalesce(delivery_station_id, station_id) 正是这个三级式的后两级）。
--     但**正常路径必须写 settle_station_id**：新代码若只靠回退，本列就退化成装饰，
--     下一个改外派流程的人又得回去猜"钱归谁"。
--
-- -----------------------------------------------------------------------------
-- 回填口径
--   `update orders set settle_station_id = coalesce(delivery_station_id, station_id)
--     where settle_station_id is null`
--   · 就是升级前**代码里一直在推导**的那个值（DashboardMapper 等），所以回填后
--     "看板/客户画像/毛利/应收"的数字与升级前**逐字一致**（用例里用同一份数据对比过）。
--   · 只写 `is null` 的行 → 二次执行影响 0 行；已显式归站的订单绝不被改写。
--   · `station_id` 本身可空（历史遗留）。两级都为空的行，settle 保持 NULL ——
--     与升级前 `coalesce(delivery_station_id, station_id)` 的结果一致（都是 NULL = 不进任何站的统计），
--     **不猜、不填 0**。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 1 个可空列 + 1 个索引 + 1 次列注释订正（`orders.station_id`，只改注释）+ 1 次有条件 UPDATE。
--     **不动任何存量列的含义与任何金额**。
--   · 写入点（改本列的地方，全部集中且可 grep）：`OrderMapper.save`（下单）+ OrderMapper 的
--     7 条改履约站的 CAS（claimPoolIfFree / dispatchIfStatus / outsourceToStationIf /
--     outsourceToPoolIf / recallToStationIf / directedReturnApproveIf / clearDispatchStation）
--     + OrderMapper.xml 的选择性 update（它也能改 delivery_station_id）。
--   · 读取口径：DashboardMapper / CustomerMapper 画像聚合 / GrossProfitMapper（含成本 join）/
--     ReceivableMapper（列表、汇总、核销校验）/ OrderMapper.countUncollected。
--   · 索引：`idx_orders_settle_station`（对齐同表的 `idx_orders_station` /
--     `idx_orders_delivery_station`；设计稿里的 `idx_settle_station` 是同一件东西）。
--     ⚠️ 上面那些统计 SQL 写的是 `coalesce(settle, delivery, station) = ?`，**函数包列用不上索引**
--     —— 这个索引不是给它们用的，是给"以后直接按结算站查"（如结算站维度的对账/导出）用的。
--
-- 幂等
--   · 加列/加索引：先查 information_schema 再 PREPARE，已存在则打印 skip 并原样放行；
--   · 列注释：与目标注释一致时 skip（`<=>` 判等，NULL 也安全）；
--   · 回填带 `settle_station_id is null` 条件 → 二次执行影响 0 行。
--
-- 回滚
--   · DROP INDEX idx_orders_settle_station ON orders; ALTER TABLE orders DROP COLUMN settle_station_id;
--     （列注释若要一并回退：`ALTER TABLE orders MODIFY COLUMN station_id bigint DEFAULT NULL
--      COMMENT '订单归属水站（交易/营收归属，客户自选）'`）
--   · 回滚**不丢任何钱的信息**：本列的值在升级前是可推导的（= coalesce(delivery_station_id,
--     station_id)），代码退回"两级 coalesce"读法即可。代价是"钱归谁"重新变回隐式 —— 也就是回到本迁移要修的那个状态。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('orders','station','customer'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（orders / station / customer 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加列（可空：NULL = 未写的防御态，读法 coalesce 兜底，见文件头）
--   ⚠️ 列注释必须与 schema.sql **逐字节一致**（README 用 HEX(COLUMN_COMMENT) 核对）
-- -----------------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'settle_station_id');
SET @ddl1 := IF(@has_col = 0,
                'ALTER TABLE orders ADD COLUMN settle_station_id bigint DEFAULT NULL COMMENT ''结算水站(v47)=本单营收归谁：水费+配送费+楼层费。下单=station_id，抢单/外派=履约站，取消外派/召回/退回池=回 station_id；押金/水票/桶权益仍按 station_id。读一律 coalesce(settle,delivery,station)'' AFTER delivery_station_id',
                'SELECT ''skip: orders.settle_station_id 已存在'' AS note');
PREPARE st1 FROM @ddl1;
EXECUTE st1;
DEALLOCATE PREPARE st1;

-- -----------------------------------------------------------------------------
-- 第 2 步：加索引（对齐同表 idx_orders_station / idx_orders_delivery_station）
-- -----------------------------------------------------------------------------
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_settle_station');
SET @ddl2 := IF(@has_idx = 0,
                'ALTER TABLE orders ADD INDEX idx_orders_settle_station (settle_station_id)',
                'SELECT ''skip: idx_orders_settle_station 已存在'' AS note');
PREPARE st2 FROM @ddl2;
EXECUTE st2;
DEALLOCATE PREPARE st2;

-- -----------------------------------------------------------------------------
-- 第 3 步：改 `orders.station_id` 的列注释
--   原注释写着「订单归属水站（交易/营收归属，客户自选）」—— v47 之后**营收不再归它**，
--   留着这句话等于把下一个人推回旧口径（"有错误注释时人会直接照做"，AGENTS §6.1）。
--   ⚠️ 只改注释、**不改类型与可空性**：MODIFY 必须逐字复刻原定义（bigint DEFAULT NULL），
--   否则会顺手改掉列语义。列注释已一致时 skip → 幂等。
-- -----------------------------------------------------------------------------
SET @cur_cmt := (SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'station_id');
SET @new_cmt := '订单归属水站（客户主动选定的站 = 定价方；营收归 settle_station_id，v47）';
SET @ddl3 := IF(@cur_cmt <=> @new_cmt,
                'SELECT ''skip: orders.station_id 注释已是 v47 口径'' AS note',
                'ALTER TABLE orders MODIFY COLUMN station_id bigint DEFAULT NULL COMMENT ''订单归属水站（客户主动选定的站 = 定价方；营收归 settle_station_id，v47）''');
PREPARE st3 FROM @ddl3;
EXECUTE st3;
DEALLOCATE PREPARE st3;

-- -----------------------------------------------------------------------------
-- 第 4 步：存量回填（口径 = 升级前代码一直在推导的那个值）
--   ⚠️ 只更新 settle_station_id is null 的行：显式归过站的行绝不被改写（二次执行 0 行）。
-- -----------------------------------------------------------------------------
UPDATE orders
SET settle_station_id = coalesce(delivery_station_id, station_id)
WHERE settle_station_id IS NULL;

SELECT CONCAT('第 4 步完成：回填影响 ', ROW_COUNT(), ' 行') AS note;

-- -----------------------------------------------------------------------------
-- 第 5 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：列定义（应为 bigint NULL）---' AS step;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND COLUMN_NAME IN ('station_id', 'settle_station_id');

SELECT '--- 校验 B：索引 ---' AS step;
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_settle_station'
GROUP BY INDEX_NAME;

SELECT '--- 校验 C：回填后仍与「两级 coalesce」不一致的行（应为 0；NULL 与 NULL 不算不一致）---' AS step;
SELECT COUNT(*) AS mismatch_rows
FROM orders
WHERE NOT (settle_station_id <=> coalesce(delivery_station_id, station_id));

SELECT '--- 校验 D：NULL 分布（station_id 为 NULL 的历史行允许留 NULL，不进任何站统计）---' AS step;
SELECT (SELECT COUNT(*) FROM orders WHERE settle_station_id IS NULL) AS settle_null_rows,
       (SELECT COUNT(*) FROM orders WHERE station_id IS NULL) AS owner_null_rows,
       (SELECT COUNT(*) FROM orders) AS orders_total;

SELECT '--- 校验 E：跨站外派单（归属 ≠ 履约）应全部结算到履约站 ---' AS step;
SELECT COUNT(*) AS cross_station_rows,
       SUM(CASE WHEN settle_station_id = delivery_station_id THEN 1 ELSE 0 END) AS settled_to_delivery
FROM orders
WHERE delivery_station_id IS NOT NULL AND station_id IS NOT NULL AND delivery_station_id <> station_id;

SELECT '--- 校验 F：金额口径未被本迁移改动（与升级前对比用）---' AS step;
SELECT COUNT(*) AS orders_total, COALESCE(SUM(total_amount), 0) AS amount_total FROM orders;

SELECT 'V47 完成：订单营收归属已有显式列 settle_station_id（水费+配送费+楼层费归它；押金/水票/桶权益仍按归属站）' AS note;
