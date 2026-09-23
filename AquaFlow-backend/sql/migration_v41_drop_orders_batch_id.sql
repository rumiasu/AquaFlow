-- =============================================================================
-- V41: 删除 orders.batch_id（挂空列清收 · docs/design/20 §6）
-- =============================================================================
-- ⚠️ 编号：在 `ls sql/` 实测之后取 v41（v38 被商品图片库工作流占用、v39 进货成本、
--    v40 客户特权）。新建迁移前先看编号，别照着上一个数字 +1。
--
-- 执行方式（必须指定库；导入走字节级重定向，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v41_drop_orders_batch_id.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- ⚠️ **破坏性 DROP：必须先上代码、再执行本脚本**（AGENTS §4）。
--    代码侧改动：`entity/Orders.java` 删掉 batchId 字段、`sql/schema.sql` 删掉该列。
--    先上代码的理由：老代码里若还有以该列名的 SELECT/INSERT，DROP 之后会立刻报 1054，
--    而那属于运行期故障而不是一条可回滚的迁移。
--
-- -----------------------------------------------------------------------------
-- 为什么删（证据，2026-09-18 全仓复核）
--
--   它是**挂空列**：全项目既没有 batch 表，也没有任何读写点。
--   复核方式与命中（0 处活引用）：
--     · Java：只有 `entity/Orders.java` 的字段声明，无 getter/setter 调用点；
--     · MyBatis：注解 SQL 与 `*.xml` mapper **都没有出现** batch_id（查写入点必须连 XML 一起查，
--       本仓曾因只 grep *.java 把 `first_barrel_order` 误判成孤儿列）；
--     · 小程序 / 前端：零命中；
--     · 其余命中全部在**不执行的历史文件**里：`sql/archive/**`、`reset_data.sql`（严禁执行）。
--   唯一真实的列确实是 NULL：`select count(*) from orders where batch_id is not null` 应为 0
--   —— 本脚本第 1 步会**自己查一遍，有值就中止 DROP**，宁可留着也不静默丢数据。
--
-- 影响面
--   · 只删一列，且该列恒为 NULL → **不动任何业务数据、不改任何金额口径**；
--   · `orders` 的其它列、索引、外键不受影响（该列上没有索引）。
--
-- 幂等：可重复执行（列不存在就 skip）。
-- 回滚：ALTER TABLE orders ADD COLUMN batch_id bigint DEFAULT NULL;
--       （回滚后该列仍恒为 NULL —— 它本来就是挂空列，回滚只是把列加回来。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('orders','station'));
SET @s := IF(@t=2,
  "SELECT '开始执行 V41（删除 orders.batch_id）' AS note",
  "SELECT 'ABORT: orders/station 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 安全检查：列存在时，先确认它确实全是 NULL。有值就**不删**（打印提示后跳过）。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='batch_id');

-- ⚠️ 这里的统计必须走动态 SQL：直接把 `batch_id` 写进一条会无条件执行的语句里，
--    在"列已经删掉"的库上 MySQL 会在**解析期**报 1054（IF 的短路救不了列解析），
--    于是"重复执行应 skip"变成"报错"，幂等就废了。
-- ⚠️ 赋值写进被 PREPARE 的语句里（`SELECT ... INTO @var`）—— **MySQL 的 EXECUTE 不支持
--    `INTO @var`**（写成 EXECUTE st INTO @x 会报 1064 语法错误，与 MariaDB 的写法不同）。
SET @s := IF(@has_col=1,
  'SELECT COUNT(*) INTO @not_null_rows FROM orders WHERE batch_id IS NOT NULL',
  'SET @not_null_rows := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 决定：不存在 → skip；有非 NULL 值 → 中止；否则 DROP
SET @s := IF(@has_col=0,
  "SELECT 'skip: orders.batch_id 不存在' AS r",
  IF(@not_null_rows>0,
    "SELECT CONCAT('ABORT: orders.batch_id 还有 ', @not_null_rows, ' 行非 NULL —— 挂空列的前提不成立，请先人工确认这些值是什么，本脚本不删') AS r",
    "ALTER TABLE orders DROP COLUMN batch_id"));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 校验
SELECT '校验A：orders.batch_id 应已不存在（空集为通过）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders' AND COLUMN_NAME='batch_id';

SELECT '校验B：orders 仍有关键列（防止 ALTER 误伤）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders'
   AND COLUMN_NAME IN ('id','status','payment_status','station_id','delivery_station_id',
                       'total_amount','water_amount','deposit_amount','create_time')
 ORDER BY COLUMN_NAME;

SELECT '校验C：业务数据未受影响' AS check_item;
SELECT (SELECT COUNT(*) FROM orders) AS orders_rows,
       (SELECT IFNULL(SUM(total_amount),0) FROM orders) AS orders_total_sum,
       (SELECT COUNT(*) FROM order_item) AS order_item_rows;
