-- =============================================================================
-- V40: 客户特权（Phase 3 · 经营收口）
-- =============================================================================
-- ⚠️ 编号：v38 被商品图片库工作流占用、v39 是进货成本，本脚本取 v40。
--    新建迁移前先 `ls sql/` 看编号。
--
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v40_customer_privilege.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17，规格见 docs/design/20 §4）
--
--   产品决定（docs/design/16 §D6）：**不做个人/企业客户的显式区分**，
--   差异化一律落到「站长在客户画像里给特权」。已有的成功先例是
--   `customer_station_config.offline_payment_enabled`（站长逐个客户开通货到付款，
--   全系统唯一控制点、无站点级总闸）—— 本表就是把这套模式一般化。
--
--   ⚠️ 三条设计约束，都来自本仓已经踩过的坑：
--
--   1) **不做通用 JSON 特权字段**。任意维度的"特权"会退化成没有校验、没有展示口径、
--      没有对账的裸状态。所以 `type` 是**有限枚举**（constant/PrivilegeType），
--      语义由类型决定 —— 与 DepositType / AdjustType / EarningKind 同一套设计语言。
--
--   2) **能影响钱的类型必须是「账户 + 流水」，不能是画像上的一个开关**。
--      一次性折扣、免配送次数这类东西本质是客户资产（与水票/押金同类），
--      做得成开关就会出现"站长说还有 3 次、客户说还有 5 次"的扯皮。
--      因此本表**只承载"不动钱"的类型**；动钱的类型（折扣率、免配送次数、允许退票）
--      在本表里也存不下语义，接口层直接**拒绝授予**（见 PrivilegeType.isImplemented）。
--      —— 这是"配了也不生效的悬空字段"那条教训的直接应用：宁可拒绝，不要静默无效。
--
--   3) **特权按 (customer, station) 隔离**：与水票/押金/桶账同一维度。
--      A 站给的特权不能在 B 站生效（否则等于跨站送钱）。
--
-- 影响面
--   · 纯新增 1 张表，**不改任何既有表/列、不动任何存量数据**；
--   · 存量客户没有任何特权行 → 一切按默认（起送量等门槛照常生效），行为零变化。
--
-- 幂等：可重复执行（表查 information_schema.TABLES）。
-- 回滚：DROP TABLE customer_privilege;
--       （回滚只失去"站长给客户开的特权"，不影响任何金额、桶账、水票、押金。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('customer','station'));
SET @s := IF(@t=2,
  "SELECT '开始执行 V40（客户特权）' AS note",
  "SELECT 'ABORT: customer/station 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 建表
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_privilege')=0,
  "CREATE TABLE customer_privilege (
     id bigint NOT NULL AUTO_INCREMENT,
     customer_id bigint NOT NULL COMMENT '客户ID',
     station_id bigint NOT NULL COMMENT '水站ID —— 特权按 (customer, station) 隔离，A 站给的不在 B 站生效',
     type varchar(32) NOT NULL COMMENT '特权类型；**有限枚举**，见 constant/PrivilegeType。本版只接受不动钱的类型',
     value varchar(64) DEFAULT NULL COMMENT '数值型特权的取值（本版唯一实现的 NO_MIN_ORDER 不用它，保留给将来的次数/折扣率）',
     note varchar(200) DEFAULT NULL COMMENT '站长备注（为什么给这个客户开）',
     operator_id bigint DEFAULT NULL COMMENT '授予人（站长员工ID）',
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (id),
     UNIQUE KEY uk_customer_privilege (customer_id, station_id, type),
     KEY idx_privilege_station (station_id)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站长按客户逐个开通的特权; 只承载不动钱的类型'",
  "SELECT 'skip: customer_privilege 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 校验
SELECT 'V40 完成：客户特权表已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_privilege'
 ORDER BY ORDINAL_POSITION;

SELECT '校验B：唯一键已建立（同一客户同站同类型只能一条）' AS check_item;
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_privilege' AND INDEX_NAME='uk_customer_privilege'
 ORDER BY SEQ_IN_INDEX;

SELECT '校验C：表为空（存量客户无任何特权，行为与升级前一致）' AS check_item;
SELECT (SELECT COUNT(*) FROM customer_privilege) AS privilege_rows,
       (SELECT COUNT(*) FROM customer) AS customer_rows;

SELECT '校验D：金额未受影响' AS check_item;
SELECT (SELECT IFNULL(SUM(total_amount),0) FROM orders) AS orders_total_sum,
       (SELECT IFNULL(SUM(quantity * COALESCE(deposit_price,0)),0) FROM inventory) AS inventory_deposit_sum;
