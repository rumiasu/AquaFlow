-- =============================================================================
-- V22: 移除「水站货到付款总闸」station.offline_payment_enabled
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   D:/backend/MySQL/bin/mysql -uroot -p123456 aquaflow --default-character-set=utf8mb4 \
--       < AquaFlow-backend/sql/migration_v22_drop_station_offline_payment.sql
--
-- ⚠️ 执行顺序要求：**先重启后端加载新代码，再执行本脚本**
--   旧字节码的 StationMapper.update 的 SQL 里仍带 `offline_payment_enabled=#{...}`，
--   若 DROP 之后还有旧进程在跑，站长保存水站信息会报 Unknown column。
--
-- -----------------------------------------------------------------------------
-- 背景（2026-09-12 定）
--   旧模型要求「水站总闸 + 客户×水站授权」两道闸同时打开，顾客端才展示货到付款。
--   但实际上：
--     1) 三个小程序（user / delivery / station）里【没有任何界面能打开总闸】，
--        建站时该列默认 0，实测两个水站的值都是 0（从未被启用过）；
--     2) 于是站长在「用户画像 → 权限设置 → 货到付款」给客户开了开关，
--        顾客端依旧不显示 —— 开关是死的，且没人能解释为什么。
--   两道闸的控制权实质重合在站长一人身上，多出的一道只增加解释成本。
--   按「站长说了算」的原则收敛为【唯一控制点 = 客户级授权】：
--       customer_station_config.offline_payment_enabled
--       由站长在用户画像里逐个客户开通；判定见 PaymentServiceImpl#canUseOfflinePayment。
--
-- 影响面审核结论（2026-09-12，已逐项核实）
--   数据库：无视图 / 触发器 / 存储过程 / 生成列 引用该列（information_schema 全查过）；
--           全库仅 station 与 customer_station_config 两个表有同名列，后者【保留】。
--   后端：  PaymentServiceImpl.canUseOfflinePayment 不再读 station；
--           Station 实体字段已删；StationMapper.update 的 SQL 已去掉该列；
--           StationController 的 /{id}/offline-payment（GET/PUT）端点已删除。
--   小程序：三端 grep 无任何引用（总闸从来没有过 UI）。
--
-- 幂等：可重复执行；MySQL 8.4 不支持 DROP COLUMN IF EXISTS，故用 information_schema 预检。
-- 回滚：把列加回来即可（`ALTER TABLE station ADD COLUMN offline_payment_enabled TINYINT NOT NULL
--       DEFAULT 0 COMMENT '已废弃'`）；历史值全为 0，无业务含义，故只备份整表不单独保列值。
-- =============================================================================

SET @db := DATABASE();

-- 0) 预检：该列是否存在（整个脚本的幂等开关）
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station' AND COLUMN_NAME='offline_payment_enabled');

-- 0.1) 留痕：删除前打印将被影响的行（重复执行时跳过，否则会因列已删而报 Unknown column）
SET @s := IF(@n>0,
  "SELECT '即将删除 station.offline_payment_enabled，以下是当前值（留痕）' AS note",
  "SELECT 'skip: 列已不存在（脚本重复执行），无需留痕' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@n>0,
  "SELECT id, name, offline_payment_enabled FROM station",
  "SELECT 'skip' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 整表备份（只建一次，重复执行不会覆盖）
CREATE TABLE IF NOT EXISTS bak_station_before_drop_offline AS SELECT * FROM station;
SELECT CONCAT('备份表 bak_station_before_drop_offline 行数 = ', COUNT(*)) AS backup_rows
  FROM bak_station_before_drop_offline;

-- 2) 删除 station.offline_payment_enabled
SET @s := IF(@n>0,
  "ALTER TABLE station DROP COLUMN offline_payment_enabled",
  "SELECT 'skip: station.offline_payment_enabled 已不存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 校验：应返回 0 行；客户级开关（唯一控制点）应仍在
SELECT 'station 残留列（应为空）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station' AND COLUMN_NAME='offline_payment_enabled';

SELECT 'customer_station_config.offline_payment_enabled（唯一控制点，应存在）' AS check_item;
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='customer_station_config' AND COLUMN_NAME='offline_payment_enabled';
