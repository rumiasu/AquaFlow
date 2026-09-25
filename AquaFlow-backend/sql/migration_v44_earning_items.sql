-- =============================================================================
-- V44: 站长自定义工资条目（加项 / 扣项字典）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   mysql -uroot --default-character-set=utf8mb4 <库名> < migration_v44_earning_items.sql
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-18）
--
--   v42 把配送员工资收窄成两项**自动**收益：每桶计件 + 楼层补贴。除此之外的加减钱
--   （迟到扣款、破损赔偿、高温补贴、临时帮忙费……）只能走 `kind='ADJUST'` 的人工调整，
--   而人工调整只有一个自由文本 `note`：
--     · 每录一笔都要重新打字，同一个扣款项在不同月份会写成「迟到」「迟到扣款」「迟到罚款」；
--     · 月底汇总不出来 —— 站长想知道「这个月迟到一共扣了多少」，只能一页页数；
--     · 打错字没有任何约束，而这是一笔**会真的从人家工资里扣掉的钱**。
--
--   本版给出的是**条目字典**：站长自己定义「加项 / 扣项 + 名称」，录钱时选条目、只填金额，
--   方向由条目决定（调用方一律传正数）。**不是**什么促销/规则引擎，也不改变任何金额口径。
--
-- -----------------------------------------------------------------------------
-- 语义
--   ① 新表 staff_earning_item：站级字典（name 同站唯一 / direction 1加项 2扣项 / status 启用停用）。
--   ② staff_earning 加两列：
--        · item_id   —— 指向条目（NULL = 不是按条目录的，即老数据与自由文本调整）
--        · item_name —— 写入时的**名称快照**
--      为什么要快照：条目改名（「迟到扣款」→「迟到罚款」）不该改写**已经发生的工资历史**，
--      与 order_item.product_name 同一条口径。汇总时优先显示条目当前名，条目没了才回落快照。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 纯新增：1 张表 + 2 个可空列。**不动任何存量数据、不改任何既有列的含义**。
--   · 存量 staff_earning 行的 item_id / item_name 均为 NULL —— 与升级前表现完全一致
--     （前端照旧显示 kindText「人工调整」+ note）。
--   · 不参与任何对账等式的新增：条目只是**标签**，金额照旧走 E-PAY（明细之和 == 结算单合计）。
--
-- 幂等
--   · 建表用 CREATE TABLE IF NOT EXISTS；加列先查 information_schema 再 PREPARE 执行。
--   · 可安全重复执行（二次执行只打印 skip）。
--
-- 回滚
--   · ALTER TABLE staff_earning DROP COLUMN item_id, DROP COLUMN item_name;
--     DROP TABLE staff_earning_item;
--   · 回滚只丢"条目分类"这一层信息，**不影响任何金额**：收益明细的 amount / payroll_id 未动。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（关键表不在就终止，绝不半途改结构）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('staff_earning','staff_payroll','staff_piece_rate'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（staff_earning / staff_payroll / staff_piece_rate 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（工资三表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：条目字典表
--   ⚠️ uk 建在 (station_id, name) 上：同站两个"高温补贴"会让月底汇总直接对不上账。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `staff_earning_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '所属水站',
  `name` varchar(20) NOT NULL COMMENT '条目名称（如 迟到扣款 / 高温补贴）',
  `direction` tinyint NOT NULL DEFAULT '1' COMMENT '方向: 1=加项(补钱) 2=扣项(扣钱); 调用方一律传正数金额',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1 启用 0 停用; 停用只挡新录入，历史流水照旧',
  `sort` int NOT NULL DEFAULT '0' COMMENT '展示顺序（小的在前）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_earning_item_name` (`station_id`,`name`) COMMENT '同站条目名不得重复',
  KEY `idx_earning_item_station` (`station_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站长自定义工资条目(加项/扣项字典)';

SELECT '第 1 步完成：staff_earning_item 已就绪' AS note;

-- -----------------------------------------------------------------------------
-- 第 2 步：staff_earning 加 item_id / item_name（先查后改，幂等）
-- -----------------------------------------------------------------------------
SET @has_item_id := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_earning' AND COLUMN_NAME = 'item_id');
SET @ddl1 := IF(@has_item_id = 0,
                'ALTER TABLE staff_earning ADD COLUMN item_id bigint DEFAULT NULL COMMENT ''自定义工资条目ID; NULL=非按条目录入(v44)'' AFTER adjustment_id',
                'SELECT ''skip: staff_earning.item_id 已存在'' AS note');
PREPARE st1 FROM @ddl1;
EXECUTE st1;
DEALLOCATE PREPARE st1;

SET @has_item_name := (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_earning' AND COLUMN_NAME = 'item_name');
SET @ddl2 := IF(@has_item_name = 0,
                'ALTER TABLE staff_earning ADD COLUMN item_name varchar(20) DEFAULT NULL COMMENT ''条目名称快照; 条目改名不改写历史(v44)'' AFTER item_id',
                'SELECT ''skip: staff_earning.item_name 已存在'' AS note');
PREPARE st2 FROM @ddl2;
EXECUTE st2;
DEALLOCATE PREPARE st2;

-- -----------------------------------------------------------------------------
-- 第 3 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：新表结构 ---' AS step;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_earning_item'
ORDER BY ORDINAL_POSITION;

SELECT '--- 校验 B：staff_earning 的新列 ---' AS step;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_earning' AND COLUMN_NAME IN ('item_id','item_name')
ORDER BY ORDINAL_POSITION;

SELECT '--- 校验 C：存量流水一律为 NULL（口径与升级前一致）---' AS step;
SELECT CONCAT('staff_earning 共 ', COUNT(*), ' 行，其中 item_id 非空 ', SUM(item_id IS NOT NULL), ' 行') AS legacy_rows
FROM staff_earning;

SELECT '--- 校验 D：唯一键确实建在 (station_id, name) 上 ---' AS step;
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_earning_item'
GROUP BY INDEX_NAME, NON_UNIQUE;

SELECT 'V44 完成：站长自定义工资条目已就绪（纯新增，不动任何金额）' AS note;
