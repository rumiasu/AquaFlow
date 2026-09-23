-- =============================================================================
-- V42: 工资口径收窄为「送水计件 + 楼层补贴」，删掉三项已停用的配置列
-- =============================================================================
-- ⚠️ 编号：在 `ls sql/` 实测之后取 v42（v38 商品图片库、v39 进货成本、v40 客户特权、
--    v41 删 orders.batch_id）。新建迁移前先看编号。
--
-- 执行方式（必须指定库；导入走字节级重定向，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v42_piece_rate_two_items.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- ⚠️ **破坏性 DROP：必须先上代码、再执行本脚本**（AGENTS §4）。
--    代码侧改动：`entity/StaffPieceRate`、`StaffPieceRateMapper.upsert`、`PayrollDTO.PieceRate`、
--    `ManagerPayrollController.savePieceRate`、`StaffEarningServiceImpl` 的五段生成逻辑、
--    以及站长端「计件工资」页的三个输入框。
--
-- -----------------------------------------------------------------------------
-- 为什么删（2026-09-18 产品决定）
--
--   站长实际只用两项：**每桶计件价** + **楼层补贴**。另外三项配置虽然有字段、有界面，
--   但真实水站不会用（回桶奖励并进桶价、每单补贴并进桶价、少收空桶扣减会直接引发劳资纠纷 ——
--   本仓的口径一贯是"欠桶只提醒不扣钱"，见 AGENTS §1）。
--
--   ⚠️ 删的是**配置项**，不是记录：少收空桶仍然有痕迹（订单 `barrel_discrepancy` +
--   `order_barrel_exception` 异常单），只是不再自动折算成扣款。
--   ⚠️ `EarningKind` 的 RETURN_BUCKET / ORDER_BONUS / PENALTY 三个常量**保留** ——
--   历史 `staff_earning` 行里还有它们，删常量会让老账的明细显示成"其他"。
--
-- 影响面
--   · 只删 `staff_piece_rate` 的 3 个配置列，**不动任何金额流水**（`staff_earning` 一行不改）；
--   · 存量水站若真配过这三项，配置会丢失 —— 所以第 1 步会把非零配置**打印出来留档**再删；
--   · 对账 E-PAY 不受影响（它比的是结算单合计与明细之和）。
--
-- 幂等：可重复执行（列不存在就 skip）。
-- 回滚：ALTER TABLE staff_piece_rate
--         ADD COLUMN return_bucket_amount decimal(10,2) NOT NULL DEFAULT '0.00',
--         ADD COLUMN per_order_amount decimal(10,2) NOT NULL DEFAULT '0.00',
--         ADD COLUMN penalty_per_bucket decimal(10,2) NOT NULL DEFAULT '0.00';
--       （回滚后三列全为默认值 0 —— 原配置不保留，值在下面的校验 A 里有留档。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('staff_piece_rate','staff'));
SET @s := IF(@t=2,
  "SELECT '开始执行 V42（工资口径收窄：删三项配置列）' AS note",
  "SELECT 'ABORT: staff_piece_rate/staff 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 留档：把这三列**非零**的存量配置打印出来（删掉就没了，至少让操作者看见删了什么）。
--    ⚠️ 必须走动态 SQL：列已经被删过时，直接写列名的语句会在**解析期**报 1054，
--    "重复执行应 skip"就变成"报错"（AGENTS §1 里 v41 那条判据）。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_piece_rate'
                   AND COLUMN_NAME='return_bucket_amount');
SET @s := IF(@has_col=1,
  'SELECT station_id, product_id, return_bucket_amount, per_order_amount, penalty_per_bucket FROM staff_piece_rate WHERE return_bucket_amount > 0 OR per_order_amount > 0 OR penalty_per_bucket > 0',
  'SELECT ''skip: 三列已不存在'' AS r');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 删除三列（列存在才删）
SET @s := IF(@has_col=1,
  'ALTER TABLE staff_piece_rate DROP COLUMN return_bucket_amount, DROP COLUMN per_order_amount, DROP COLUMN penalty_per_bucket',
  'SELECT ''skip: 三列已不存在'' AS r');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 校验
SELECT '校验A：这三列应已不存在（空集为通过）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_piece_rate'
   AND COLUMN_NAME IN ('return_bucket_amount','per_order_amount','penalty_per_bucket');

SELECT '校验B：剩下的列应正好是这两项工资依据 + 键 + 时间戳' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_piece_rate' ORDER BY ORDINAL_POSITION;

SELECT '校验C：金额流水未受影响（工钱明细一行都没动）' AS check_item;
SELECT (SELECT COUNT(*) FROM staff_earning) AS earning_rows,
       (SELECT IFNULL(SUM(amount),0) FROM staff_earning) AS earning_sum,
       (SELECT COUNT(*) FROM staff_payroll) AS payroll_rows;
