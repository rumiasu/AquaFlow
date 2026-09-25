-- =============================================================================
-- V58: 水站「统一折扣」档位（2026-09-20）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v58_station_ticket_discount.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（产品 2026-09-20 澄清，纠正 v54 的形态）
--   产品原话：「统一水票，在**站长端是特殊化的**，但在**用户端看起来没区别**，
--   执行上**也不是统一定价**，而是**对应水怎么统一打折、统一打几折**的区别，
--   **不是专门卖统一水票**。」
--
--   ⇒ 「统一」统一的是**折扣率**（站级一处配：买 10 张 9.5 折、30 张 9 折），
--     而**价格按各款水自己的水票价折算** —— 农夫山泉按农夫山泉的价打 9.5 折、
--     娃哈哈按娃哈哈的价打 9.5 折。**不存在"站级一张统一定价的票"**。
--
--   所以 v54 那套"站级通用票账户（product_id = 0）"是**形态理解错了**，本批按 B 方案收口
--   （产品 2026-09-20 拍板：按统一折扣买的票进**该商品**的账户，只能抵那款水）：
--     · 买票：客户在某款水那一栏买（就像买定制票一样），票进**该款水的账户**；
--     · 定价：该款水的水票价 × 站级折扣档的折扣率（服务端算，客户端不传价）；
--     · 用票：抵扣仍是"该商品的账户"，与定制票**完全同一条路**；
--     · 「定制优先」= 该商品自己配了定制票（`inventory.ticket_enabled = 1`）就用定制的
--       那套（散买按站级水票价、档位按站长挂的绝对价目表）；没配才轮到统一折扣。
--
--   本表只存**折扣**（张数 + 千分比），**不存价格** —— 价格必须按商品现算，
--   存下来就立刻会与"各款水的价"分叉（那正是 v54 做错的地方）。
--
-- 影响面
--   · 纯新增 1 张表 + 1 个唯一键；**不改任何既有表、不动任何存量数据**；
--   · ⚠️ 真实库实测 `ticket_record` / `ticket_account` / `ticket_package` **均为 0 行**
--     （功能已上线但没人配过档位、没人买过票）→ 本次形态收口**零数据迁移成本**。
--   · v54 那一列 `ticket_record.account_product_id` 已随本批代码改为不写不读，
--     删除脚本见 `migration_v59_drop_ticket_account_product.sql`（**破坏性，按规程单独执行**）。
--
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 回滚：DROP TABLE station_ticket_discount;
-- =============================================================================

SET @db := DATABASE();

SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station')>0,
  "SELECT '开始执行 V58（水站统一折扣档位 station_ticket_discount）' AS note",
  "SELECT 'ABORT: 当前库没有 station 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS `station_ticket_discount` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '水站ID（折扣是站级设置）',
  `qty` int NOT NULL COMMENT '本档张数（如 10 / 30 / 100）',
  `discount_per_mille` int NOT NULL COMMENT '折扣千分比：950 = 9.5 折、900 = 9 折（整数运算，避免浮点误差）',
  `title` varchar(32) DEFAULT NULL COMMENT '展示名（可空；为空时前后端一律按「N 张 X 折」生成，不要各写一套）',
  `status` tinyint DEFAULT '1' COMMENT '1 上架 0 下架。本站"有没有上架的统一折扣档" = 统一折扣是否生效（不另设开关列）',
  `sort` int DEFAULT '0' COMMENT '排序，小的在前',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_ticket_discount` (`station_id`,`qty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='水站「统一折扣」档位（v58）：某款水没有自己的定制票时，按这张表把该款水的价打折卖票';

-- 自检
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_ticket_discount') AS table_ready,
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_ticket_discount'
      AND INDEX_NAME='uk_station_ticket_discount') AS unique_key_cols,
  (SELECT COUNT(*) FROM station_ticket_discount) AS rows_now;
