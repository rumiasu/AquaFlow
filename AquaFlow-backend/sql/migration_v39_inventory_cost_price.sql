-- =============================================================================
-- V39: 进货成本（Phase 3 · 经营收口）
-- =============================================================================
-- ⚠️ 编号说明：v38 已被另一个工作流占用（商品图片库，工作区里的
--    migration_v38_platform_product_images.sql）。本脚本取 v39，避免撞号。
--
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v39_inventory_cost_price.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17，规格见 docs/design/20 §2）
--
--   站长**看不到自己赚多少**：此前 product 只有售价、inventory 只有站级售价与押金，
--   全仓检索 cost_price|成本|进价|毛利 在业务代码里**零命中**。
--   而"这桶水我进价多少、卖多少、这单赚几块"是水站最日常的一个问题。
--
--   本脚本只加**一个字段**：`inventory.cost_price`（站级当前进货成本）。
--
--   ⚠️ 刻意**不做**的一件事：不加供应商表、不加采购单、不加应付账款、不加批次成本核算。
--   理由（docs/design/16 §D4）：本轮范围明确不做上游供应链 —— 那是 ERP 的量级。
--   代价要说清楚：**成本变了之后，历史毛利会用新成本重算**。
--   对独立小水站这是可接受的（他们算账就是这么算的），但必须在文档与接口文案里写明，
--   不能让站长以为看到的是"当时的真实毛利"。
--
--   ⚠️ 另一个刻意选择：成本落在 **inventory（站×商品）** 而不是 product（通用商品库）。
--   进货价是每个水站自己的事 —— 同一个品牌的桶装水，不同水站的进货渠道与价格不同，
--   放到通用库等于替站长定价，也等于让 A 站的成本泄露给 B 站。
--
-- 影响面
--   · 纯新增 1 个可空列，**不改任何既有列的类型与含义、不动任何存量数据**；
--   · 存量 inventory 行 cost_price 为 NULL → 毛利报表里该商品显示"未填成本"，
--     成本额按 0 计、**不显示毛利**（而不是显示"毛利 = 全额售价"，那会严重误导）。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS）。
-- 回滚：ALTER TABLE inventory DROP COLUMN cost_price;
--       （回滚只失去"成本价"，不影响任何售价、库存、订单金额、桶账、水票、押金。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory');
SET @s := IF(@has_tbl>0,
  "SELECT '开始执行 V39（进货成本）' AS note",
  "SELECT 'ABORT: 当前库没有 inventory 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) inventory.cost_price
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory'
                AND COLUMN_NAME='cost_price')=0,
  "ALTER TABLE inventory ADD COLUMN cost_price decimal(10,2) NULL DEFAULT NULL COMMENT '本站进货成本单价（NULL=未填，毛利报表会标注未填而不是按 0 算成全额毛利）' AFTER sale_price",
  "SELECT 'skip: inventory.cost_price 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 校验
SELECT 'V39 完成：进货成本字段已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory' AND COLUMN_NAME='cost_price';

SELECT '校验B：存量行成本全为 NULL（未填）' AS check_item;
SELECT COUNT(*) AS inventory_rows,
       SUM(CASE WHEN cost_price IS NULL THEN 1 ELSE 0 END) AS null_cost_rows
  FROM inventory;

SELECT '校验C：售价/押金/库存未受影响' AS check_item;
SELECT ROUND(SUM(COALESCE(sale_price,0)),2) AS sale_sum,
       ROUND(SUM(COALESCE(deposit_price,0)),2) AS deposit_sum,
       SUM(quantity) AS qty_sum
  FROM inventory;
