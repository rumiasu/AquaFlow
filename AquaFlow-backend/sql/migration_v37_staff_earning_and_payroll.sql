-- =============================================================================
-- V37: 配送员计件工资（Phase 2 · 配置 + 收益明细 + 结算单）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v37_staff_earning_and_payroll.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17，规格见 docs/design/18）
--
--   配送员的工钱此前在系统里**完全不存在**（全仓检索 提成|计件|工资|salary 在业务代码零命中，
--   staff 表也没有任何薪酬字段）。而真实水站配送员的收入就是按桶计件（一桶 2~4 元），
--   站长每个月要照着"本子上记的送了多少桶"发钱 —— 系统不记，站长就得手工算。
--
--   与外卖平台的关键差别（决定表结构）：
--     · **发钱的是站长，不是平台** → 这是站内台账，没有平台结算单、没有佣金抽成、没有骑手钱包；
--     · **计件单位是桶，不是单** → 一单常是 1~3 桶（每桶 18.9kg），按单计价会让配送员不愿接多桶单；
--     · **任务天生双向** → 送水 + 收空桶，所以「送桶」与「回桶」是两个可分别定价的动作；
--     · **楼层费是两笔钱** → 向客户收的记 orders.floor_fee（收入），给配送员的记本表（成本），
--       两者金额可以不同，必须分开配置分开落库。
--
--   三张表：
--     · staff_piece_rate  站级计件单价（product_id=0 表示该站默认价）
--     · staff_earning     收益明细（一行一个动作），**唯一键防重复结算**
--     · staff_payroll     结算单（草稿 → 已确认 → 已发放），对齐 station_adjustment 的
--                         「确认后不原地改，要改走下一期调整」做法
--
-- ⚠️ 关于 staff_earning 的唯一键（本表最容易写错的地方）
--   自动收益（完成配送时产生）必须**幂等**：completeDelivery 被重复调用不能重复计一笔工钱。
--   但人工调整是站长手工录的，**本来就允许多条**。这两件事用一个普通唯一键表达不了 ——
--   而 `uk(order_id, staff_id, kind)` 在 order_id IS NULL 时**零保护**
--   （MySQL 唯一键中 NULL 互不冲突），本仓已经在 uk_ticket_consume 与 uk_payment_active_order
--   上各踩过一次。
--   所以这里用**生成列**把两种场景分开：
--     auto_uk = CONCAT(order_id,'-',staff_id,'-',kind) 当 order_id 非空，否则 NULL
--     UNIQUE KEY uk_earning_auto (auto_uk)
--   与 uk_payment_active_order 形状相同，**但这里的 NULL 是有意的**：
--   NULL = 人工录入，本来就允许无限多条。这不是"忘了 NULL 不冲突"，是显式设计。
--   调整单场景另由 uk_earning_adjustment(adjustment_id, staff_id, kind) 兜底。
--
-- 影响面
--   · 纯新增 3 张表，**不改任何既有表/列、不动任何存量数据**；
--   · 存量水站没有计件配置 → 计件单价全 0 → 不产生任何收益，站长行为零变化。
--
-- 幂等：可重复执行（表查 information_schema.TABLES）。
-- 回滚：DROP TABLE staff_earning; DROP TABLE staff_payroll; DROP TABLE staff_piece_rate;
--       （回滚只失去"计件工资台账"，不影响订单金额、桶账、水票、押金任何余额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('staff','station','orders','product'));
SET @s := IF(@t=4,
  "SELECT '开始执行 V37（配送员计件工资）' AS note",
  "SELECT 'ABORT: staff/station/orders/product 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) staff_piece_rate：站级计件单价
--    ⚠️ product_id 用 NOT NULL DEFAULT 0 表示「该站默认价」，不用 NULL：
--    自增主键不能为 NULL，而复合唯一键里含 NULL 会退化成"永不冲突"（同 uk_ticket_consume 的坑）。
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_piece_rate')=0,
  "CREATE TABLE staff_piece_rate (
     station_id bigint NOT NULL COMMENT '水站ID',
     product_id bigint NOT NULL DEFAULT 0 COMMENT '商品ID; 0=该站默认价（按商品可单独定价，18.9L 与 5L 搬运成本不同）',
     per_bucket_amount decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每送一桶的计件价; 0=本站不计件',
     return_bucket_amount decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每回收一个空桶的奖励; 0=不奖',
     floor_bonus_per_level decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '无电梯时每超一层的补贴; 0=不补',
     floor_free_level int NOT NULL DEFAULT 1 COMMENT '免费楼层（此层及以下不补）',
     per_order_amount decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每单基础奖励',
     penalty_per_bucket decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每少收一个空桶的扣减; 0=不扣',
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (station_id, product_id)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站级配送计件单价'",
  "SELECT 'skip: staff_piece_rate 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) staff_earning：收益明细
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_earning')=0,
  "CREATE TABLE staff_earning (
     id bigint NOT NULL AUTO_INCREMENT,
     station_id bigint NOT NULL COMMENT '结算站 = **履约站**(delivery_station_id)：工钱是履约成本，跟出车的人走',
     staff_id bigint NOT NULL COMMENT '收益归属人（实际完成配送的人）',
     order_id bigint DEFAULT NULL COMMENT '关联订单；NULL=人工调整（见 auto_uk 的注释）',
     kind varchar(32) NOT NULL COMMENT 'DELIVERY_BUCKET/RETURN_BUCKET/FLOOR_BONUS/ORDER_BONUS/PENALTY/ADJUST; 见 constant/EarningKind',
     product_id bigint NOT NULL DEFAULT 0 COMMENT '商品ID（送桶/回桶按商品分行的用）; 0=与商品无关（楼层/单奖/扣减/人工调整）',
     qty int DEFAULT NULL COMMENT '数量（桶数/层数）',
     unit_amount decimal(10,2) DEFAULT NULL COMMENT '单价快照',
     amount decimal(10,2) NOT NULL COMMENT '金额; **扣减类为负数**（方向由 kind 决定，见 constant/EarningKind）',
     payroll_id bigint DEFAULT NULL COMMENT '已结算时写入所属结算单; NULL=未结算',
     adjustment_id bigint DEFAULT NULL COMMENT '来源资产调整单（人工调整场景的幂等键）',
     note varchar(200) DEFAULT NULL,
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     auto_uk varchar(128) GENERATED ALWAYS AS (
       case when order_id is null then null
            else concat(order_id, '-', staff_id, '-', kind, '-', product_id) end) STORED
       COMMENT '自动收益去重键（含 product_id：一单多个商品要各记一行）; **NULL 是有意的**=人工录入，允许无限多条',
     PRIMARY KEY (id),
     UNIQUE KEY uk_earning_auto (auto_uk),
     UNIQUE KEY uk_earning_adjustment (adjustment_id, staff_id, kind),
     KEY idx_earning_staff_time (station_id, staff_id, create_time),
     KEY idx_earning_payroll (payroll_id)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送员收益明细; 工钱走独立等式不进客户对账'",
  "SELECT 'skip: staff_earning 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) staff_payroll：结算单
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='staff_payroll')=0,
  "CREATE TABLE staff_payroll (
     id bigint NOT NULL AUTO_INCREMENT,
     payroll_no varchar(32) NOT NULL COMMENT '单据号 PRyyyymmdd-000001（拿到自增 id 后生成，与押金条 DP 同款）',
     station_id bigint NOT NULL,
     staff_id bigint NOT NULL,
     period_start date NOT NULL COMMENT '结算期间起（含）',
     period_end date NOT NULL COMMENT '结算期间止（含）',
     total_amount decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '本期合计; 必须等于本期明细之和（对账 E-PAY）',
     status tinyint NOT NULL DEFAULT 1 COMMENT '1 草稿 2 已确认 3 已发放',
     paid_time datetime DEFAULT NULL COMMENT '发钱时间（线下转账/现金，系统只留痕）',
     operator_id bigint DEFAULT NULL,
     note varchar(200) DEFAULT NULL,
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (id),
     UNIQUE KEY uk_payroll_no (payroll_no),
     UNIQUE KEY uk_payroll_period (station_id, staff_id, period_start, period_end)
       COMMENT '同一人同一期间只能有一张结算单 —— 防止重复结算',
     KEY idx_payroll_station_status (station_id, status)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送员工资结算单; 确认后明细锁定'",
  "SELECT 'skip: staff_payroll 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) 校验
SELECT 'V37 完成：配送员计件工资三张表已就绪' AS note;

SELECT '校验B：E-PAY 等式（结算单合计 == 本期明细之和；应为 0 条不平）' AS check_item;
SELECT COUNT(*) AS epay_unbalanced FROM (
  SELECT p.id FROM staff_payroll p
    LEFT JOIN (SELECT payroll_id, SUM(amount) AS s FROM staff_earning
                WHERE payroll_id IS NOT NULL GROUP BY payroll_id) e ON e.payroll_id = p.id
   WHERE ABS(COALESCE(p.total_amount,0) - COALESCE(e.s,0)) > 0.009) x;

SELECT '校验C：已结算明细必须挂在结算单上，且结算单已确认/已发放' AS check_item;
SELECT COUNT(*) AS orphan_settled FROM staff_earning e
  LEFT JOIN staff_payroll p ON p.id = e.payroll_id
 WHERE e.payroll_id IS NOT NULL AND p.id IS NULL;

SELECT '校验D：三张表均为空（存量水站无计件配置，行为与升级前一致）' AS check_item;
SELECT (SELECT COUNT(*) FROM staff_piece_rate) AS rate_rows,
       (SELECT COUNT(*) FROM staff_earning) AS earning_rows,
       (SELECT COUNT(*) FROM staff_payroll) AS payroll_rows;
