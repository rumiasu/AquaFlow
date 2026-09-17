-- =============================================================================
-- V32: 水站营业状态（软状态）+ 站长留言
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v32_station_operating_status.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17 产品口径）
--   站长需要表达"现在什么情况"：正常运营 / 休息中 / 配送延迟 / 暂停配送可预约，
--   并且能写一句留言（"今天休息，明早 8 点正常送"）。**产品明确要求：不阻断下单，只弹提示。**
--
--   ⚠️ 因此**不能复用 `station.status`**：
--     · station.status = 1 营业 / 2 停业 是**硬状态** —— `OrderServiceImpl` 遇到 2 会直接
--       抛"水站不存在或已停业"拒绝下单，且 `/api/stations/public` 只列 status=1。
--     把"休息中"塞进这一列，等于把"休息"实现成"关张"，会真的挡住客户下单。
--   本脚本新增的三列是**软状态**，只影响展示与提示（商城/下单页横幅 + 下单响应 warnings）。
--
-- 语义（应用侧唯一口径：constant/StationOperatingStatus）
--   · operating_status: 1 正常运营（默认）/ 2 休息中 / 3 配送延迟 / 4 暂停配送可预约
--   · status_note:      站长留言，≤100 字，展示给顾客
--   · status_update_time: 最近一次修改时间（顾客提示里显示"刚刚更新"）
--
-- 影响面
--   · 纯新增 3 列，**不改任何既有列的类型与含义、不动任何存量数据**；
--   · 存量水站 operating_status 自动取默认值 1（= 正常运营，与现状一致，前端零感知）；
--   · 顾客侧可见性：`/api/stations/public`（实体自动带出）与新增公开端点
--     `GET /api/stations/{id}/status`；**不含任何站长私有字段**。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS）。
-- 回滚：ALTER TABLE station DROP COLUMN status_update_time, DROP COLUMN status_note,
--       DROP COLUMN operating_status;
--       （回滚只丢"营业状态与留言"的展示信息，不影响订单、桶账、水票、押金任何金额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station');
SET @s := IF(@has_tbl>0,
  "SELECT '开始执行 V32（水站营业状态软状态 + 站长留言）' AS note",
  "SELECT 'ABORT: 当前库没有 station 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) operating_status
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station'
                AND COLUMN_NAME='operating_status')=0,
  "ALTER TABLE station ADD COLUMN operating_status tinyint NOT NULL DEFAULT 1 COMMENT '营业软状态（不阻断下单，只给顾客提示）: 1正常运营 2休息中 3配送延迟 4暂停配送可预约; 见 constant/StationOperatingStatus' AFTER status",
  "SELECT 'skip: station.operating_status 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) status_note（站长留言）
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station'
                AND COLUMN_NAME='status_note')=0,
  "ALTER TABLE station ADD COLUMN status_note varchar(100) NULL DEFAULT NULL COMMENT '站长留言：配合营业状态的一句话说明，展示给顾客（≤100字）' AFTER operating_status",
  "SELECT 'skip: station.status_note 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) status_update_time
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station'
                AND COLUMN_NAME='status_update_time')=0,
  "ALTER TABLE station ADD COLUMN status_update_time datetime NULL DEFAULT NULL COMMENT '营业状态最近一次修改时间' AFTER status_note",
  "SELECT 'skip: station.status_update_time 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) 存量回填：默认值 1（正常运营）已在 DEFAULT 里，无需 UPDATE。
--    这里只把 NULL 兜成 1（MySQL 加 NOT NULL 列时已有行会填默认值，但历史导入/手工插入可能留 NULL）。
UPDATE station SET operating_status = 1 WHERE operating_status IS NULL;

-- 5) 校验
SELECT 'V32 完成：station 营业软状态三列已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station'
   AND COLUMN_NAME IN ('operating_status','status_note','status_update_time')
 ORDER BY ORDINAL_POSITION;

SELECT '校验B：取值分布（应全部为 1=正常运营）' AS check_item;
SELECT operating_status, COUNT(*) AS cnt FROM station GROUP BY operating_status;

SELECT '校验C：行数未受影响' AS check_item;
SELECT (SELECT COUNT(*) FROM station) AS station_rows,
       (SELECT COUNT(*) FROM staff) AS staff_rows,
       (SELECT COUNT(*) FROM orders) AS orders_rows;
