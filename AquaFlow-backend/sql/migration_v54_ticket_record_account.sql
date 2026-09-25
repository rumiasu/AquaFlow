-- =============================================================================
-- V54: 统一水票（站级通用票）—— 给 ticket_record 补「这一笔扣的是哪个账户」
-- =============================================================================
-- ⚠️⚠️ **本迁移已被 v59 撤回（2026-09-20）**：产品澄清「统一水票在站长端是特殊化的，但在用户端
-- 看起来没区别，执行上也不是统一定价，而是对应水怎么统一打折、统一打几折的区别，不是专门卖统一水票」
-- + 拍板「按统一折扣买的票进**该商品**的账户、只能抵那款水」→ "订单行商品"与"扣票账户"**恒等**，
-- 本脚本加的 `account_product_id` 不再承载任何信息。撤回脚本见
-- `migration_v59_drop_ticket_account_product.sql`（破坏性 DROP，先上代码再执行）。
--
-- 本文件**保留不改**（历史事实：真实库确实执行过它），仅在此标注终态；新的形态见
-- `migration_v58_station_ticket_discount.sql` 与 `docs/design/26` §26.11。
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v54_ticket_record_account.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-19 产品裁定；规格见 docs/design/26）
--   产品原话：「定制优先…**统一水票**是可以设置项，比如买 10 张都打 9.5 折、30 张统一 9 折这种，
--   定制和统一都有的情况下，定制优先，**统一的仅在没有定制水票的桶时生效**」。
--
--   统一票 = **站级通用票账户**，用 `product_id = 0` 表达（水票四张表的 product_id 都**没有外键**，
--   实测 information_schema.KEY_COLUMN_USAGE 为空 → 零结构障碍）。
--
--   扣票时选哪个账户由 util/TicketScope 一处判定；但**退款必须回到当初扣的那个账户** ——
--   而退款时刻账户余额已经变了，重新判定会算出另一个答案（客户中途又买了定制票就会退错账户）。
--   所以这一笔必须**自证**：消费流水上记下"扣自哪个账户"。
--
--   为什么不复用 product_id：`ticket_record` 的唯一键 `uk_ticket_consume(order_id, product_id, source)`
--   用 product_id 保证"同一单同一商品只扣一次"（逐项幂等）。若把统一票的流水也写成 product_id=0，
--   同一单里两个都走统一票的商品会撞唯一键 → 第二条被当成"并发重复"静默跳过 → **少扣一张票**。
--   因此：`product_id` 保持"订单行商品"，新增 `account_product_id` 记"扣自哪个账户"。
--
-- 语义
--   · NULL  = 与 product_id 同账户（**存量行全部是这种**：那时还没有统一票）
--   · 0     = 站级通用票账户（统一水票）
--   · 其它  = 该商品的定制票账户（显式写下，便于追溯）
--
-- 影响面
--   · 纯新增 1 个**可空**列：**不改任何既有列/含义、不动任何存量数据**（存量行保持 NULL，
--     读侧把 NULL 解释为"= product_id"，与升级前逐字一致）；
--   · 不带索引：它只随流水一起读（退款按 (order_id, product_id) 定位流水，再顺带读本列）。
--
-- 幂等：可重复执行（查 information_schema.COLUMNS）。
-- 回滚：ALTER TABLE ticket_record DROP COLUMN account_product_id;
--       （回滚只会丢掉"扣自哪个账户"这一条自证信息；统一票本身也会因判据缺失而不再生效。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_ticket_record := (SELECT COUNT(*) FROM information_schema.TABLES
                           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record');
SET @s := IF(@has_ticket_record>0,
  "SELECT '开始执行 V54（统一水票：ticket_record.account_product_id）' AS note",
  "SELECT 'ABORT: 当前库没有 ticket_record 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 加列（可空，无默认值写入）
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
                AND COLUMN_NAME='account_product_id')=0,
  "ALTER TABLE ticket_record ADD COLUMN account_product_id bigint NULL DEFAULT NULL COMMENT '扣票账户的商品ID: NULL=与 product_id 同账户(存量/定制), 0=站级通用票(统一水票); 退款必须回到这个账户'",
  "SELECT 'skip: ticket_record.account_product_id 已存在' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 自检：列在、且存量行全为 NULL（不动存量数据）
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record'
      AND COLUMN_NAME='account_product_id') AS column_ready,
  (SELECT COUNT(*) FROM ticket_record) AS total_records,
  (SELECT COUNT(*) FROM ticket_record WHERE account_product_id IS NOT NULL) AS records_with_account,
  (SELECT COUNT(*) FROM ticket_package WHERE product_id = 0) AS unified_packages;
