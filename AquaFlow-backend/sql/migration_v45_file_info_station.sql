-- =============================================================================
-- V45: file_info 站隔离（补 station_id + 存量回填 + 索引）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   mysql -uroot --default-character-set=utf8mb4 <库名> < migration_v45_file_info_station.sql
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-18）
--
--   2026-09-16 的死端点评估（docs/audit/2026-09-16-死端点评估.md §8.23）登记过一个**未修的跨站可见**：
--     `GET /api/files` 走的 `FileInfoMapper.listAll()` / `listByCategory` **没有任何水站过滤**，
--     而 `file_info` 表**连 station_id 列都没有** —— 于是任何站长的 token 都能列出
--     **全部水站**的文件名与可用临时 URL（COS 预签名地址）。这属于跨租户数据泄露，
--     因此当时的结论是「接线前必须先做站隔离，否则就整族删掉」。
--
--   本迁移补的是**隔离**这一半（另一半是查询过滤，在 FileInfoMapper 里改）。
--
-- -----------------------------------------------------------------------------
-- 语义（务必按这个口径读）
--   · station_id 非空 = 该水站上传的文件，**只有本站站长可见/可删**；
--   · station_id 为 NULL = **平台级**文件（预置 banner、开发者维护的通用图等）—— 全站可见。
--     ⚠️ 这个 NULL 是**有意保留的合法状态**，不是"没回填上"：把它当成脏数据清掉，
--     会让平台预置资源从所有站长的列表里消失。
--   · 存量回填口径：按 `uploader_id` 反查 `staff.station_id`；**查不到就保持 NULL**（视为平台级）。
--     宁可少隔离几条历史记录，也不猜——猜错会把某个站的文件暴露给另一个站。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 纯新增：1 个可空列 + 1 个索引 + 1 次有条件 UPDATE（只改 station_id 为 NULL 的存量行）。
--   · 不动任何既有列的含义、不动任何文件本体、不动 COS 上的对象。
--   · 对业务零影响：file_info 只服务于文件管理列表，不参与计价/库存/对账/订单。
--
-- 幂等
--   · 加列/加索引先查 information_schema 再 PREPARE；回填带 `station_id is null` 条件
--     → 二次执行影响 0 行。
--
-- 回滚
--   · DROP INDEX idx_file_station ON file_info; ALTER TABLE file_info DROP COLUMN station_id;
--   · 回滚只丢"这条文件属于哪个站"的信息，不影响文件本体与 COS 对象。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('file_info','staff','station'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（file_info / staff / station 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加列（可空：NULL 是有语义的"平台级"，见文件头）
-- -----------------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'station_id');
SET @ddl1 := IF(@has_col = 0,
                'ALTER TABLE file_info ADD COLUMN station_id bigint DEFAULT NULL COMMENT ''归属水站(v45); NULL=平台级文件(全站可见)'' AFTER category',
                'SELECT ''skip: file_info.station_id 已存在'' AS note');
PREPARE st1 FROM @ddl1;
EXECUTE st1;
DEALLOCATE PREPARE st1;

-- -----------------------------------------------------------------------------
-- 第 2 步：加索引（列表查询是 (station_id, category) 双条件）
-- -----------------------------------------------------------------------------
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file_info' AND INDEX_NAME = 'idx_file_station');
SET @ddl2 := IF(@has_idx = 0,
                'ALTER TABLE file_info ADD INDEX idx_file_station (station_id, category)',
                'SELECT ''skip: idx_file_station 已存在'' AS note');
PREPARE st2 FROM @ddl2;
EXECUTE st2;
DEALLOCATE PREPARE st2;

-- -----------------------------------------------------------------------------
-- 第 3 步：存量回填（只按 uploader_id → staff.station_id，查不到保持 NULL）
--   ⚠️ 只更新 station_id is null 的行：已归属的行绝不被改写（二次执行为 0 行）。
-- -----------------------------------------------------------------------------
UPDATE file_info f
JOIN staff s ON s.id = f.uploader_id
SET f.station_id = s.station_id
WHERE f.station_id IS NULL
  AND s.station_id IS NOT NULL;

SELECT CONCAT('第 3 步完成：回填影响 ', ROW_COUNT(), ' 行') AS note;

-- -----------------------------------------------------------------------------
-- 第 4 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：归属分布（NULL = 平台级，是有意保留的）---' AS step;
SELECT IFNULL(station_id, 'NULL(平台级)') AS station, COUNT(*) AS files
FROM file_info GROUP BY station_id ORDER BY station_id;

SELECT '--- 校验 B：仍有可回填却未回填的行？（应为 0）---' AS step;
SELECT COUNT(*) AS backfill_leftover
FROM file_info f JOIN staff s ON s.id = f.uploader_id
WHERE f.station_id IS NULL AND s.station_id IS NOT NULL;

SELECT '--- 校验 C：列与索引 ---' AS step;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file_info' AND COLUMN_NAME = 'station_id';
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'file_info' AND INDEX_NAME = 'idx_file_station'
GROUP BY INDEX_NAME;

SELECT 'V45 完成：文件已可按水站隔离（NULL = 平台级，全站可见）' AS note;
