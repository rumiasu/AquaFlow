-- =============================================================================
-- v61 · 新注册水站默认「待上线」；3 号位从「配送延迟」改名为「待上线」
--
-- 产品裁定（2026-09-23）：
--   「水站正常运营需要填完设置啊。营业状态默认是待上线状态，刚注册的一律都是…
--     配送延迟换成待上线吧。」
--
-- 为什么要有「待上线」这个状态：
--   新站注册完什么都没有 —— 没坐标（配送范围整段失效）、没上架商品（客户下不了单）、
--   没保存过配送计费（起送量/运费一条都没生效）。这些缺口原先**只体现为"功能静默失效"**，
--   站长自己看不出来（判据清单见 `docs/design/25-站长信息完善引导.md` 与
--   `StationSetupGuideService`）。默认「正常运营」等于**替站长宣布"我开张了"**。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · **只改列默认值 + 列注释，不改任何存量行的值、不动任何金额。**
--     `station.operating_status` 仍是 tinyint NOT NULL，取值域仍是 1..4。
--   · ⚠️ **值 3 的语义被替换（属"删掉一个能力"，AGENTS §0.3）**：
--     原「配送延迟」（照常接单、送达会比平时晚：爆单/天气/人手不足）**从此不存在**。
--     要表达"晚送"请用 2「休息中」或写进 `status_note` 留言。
--     墓碑注释留在 `constant/StationOperatingStatus.PENDING_LAUNCH`。
--   · **执行前已核对存量数据**（2026-09-23，本机真实库 `aquaflow`）：
--     `SELECT operating_status, COUNT(*) FROM station GROUP BY operating_status`
--     → `1 → 2 行`，**没有任何一行是 3** —— 所以这次换语义不会静默改到任何存量水站。
--     ⚠️ 换到别的库执行前请自己再跑一遍上面那句；**若有行 = 3，先决定它们该算「待上线」
--     还是该改回某个在营状态**（本脚本不做数据变更，是有意的：迁移不该替站长猜）。
--   · 生效范围只有**此后新建**的水站：`StationMapper.insert` 不写这一列
--     （`INSERT INTO station(name, phone, address, lat, lng, status, ...)`），吃列默认值。
--   · 判定与写入点（可 grep）：
--     文案/合法性 = `constant/StationOperatingStatus`（`textOf` / `descOf` / `allText`）；
--     站长写 = `ManagerStationStatusController` → `StationMapper.updateOperatingStatus`；
--     顾客读 = `StationController`（公开端点）/ `station` 实体自动带出；
--     待填项 = `StationSetupGuideService` + `GET /api/manager/setup-guide`
--     （2026-09-23 起每条带机器可读 `route`，供"点击直接前往"）。
--   · 索引：不加（本列从不作为查询条件，只在读取整行时带出）。
--
-- 幂等
--   · 先查 `information_schema.COLUMNS.COLUMN_DEFAULT`，已是 3 就打印 skip；
--   · 本迁移**不含任何 UPDATE / DELETE / INSERT**，二次执行无副作用。
--
-- 回滚
--   · `ALTER TABLE station MODIFY COLUMN operating_status tinyint NOT NULL DEFAULT 1
--      COMMENT '…旧注释…';`，并把 `StationOperatingStatus` 的 3 号位改回「配送延迟」。
--   · 回滚**不丢钱、不丢订单**：这一列只是给顾客看的软状态提示（不阻断下单）。
--     ⚠️ 但会把"新建站默认待上线"这个保护一并撤掉 —— 新站又会默认宣布自己正常运营。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（关键表不在就中止，避免在别的库上瞎改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('station','orders','customer'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（station / orders / customer 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：把列默认值 1 → 3，并同步列注释里的取值说明
--   ⚠️ 列注释必须与 sql/schema.sql **逐字节一致**（README 用 HEX(COLUMN_COMMENT) 核对）
-- -----------------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'station'
                   AND COLUMN_NAME = 'operating_status');

SET @sql := IF(@has_col = 0,
    'SELECT ''ABORT: station.operating_status 不存在 —— 本迁移基于 v32，请先补跑 v32''',
    IF((SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'station'
           AND COLUMN_NAME = 'operating_status') = '3',
       'SELECT ''skip: operating_status 默认值已是 3''',
       'ALTER TABLE station MODIFY COLUMN operating_status tinyint NOT NULL DEFAULT 3 COMMENT ''营业软状态（不阻断下单，只给顾客提示）: 1正常运营 2休息中 3待上线(新站默认) 4暂停配送可预约; 见 constant/StationOperatingStatus'''));
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 2 步：核对（默认值应为 3；行分布应保持原样 —— 本脚本不该动任何行）
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, HEX(COLUMN_COMMENT) AS comment_hex
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'station'
   AND COLUMN_NAME = 'operating_status';

SELECT operating_status, COUNT(*) AS cnt
  FROM station
 GROUP BY operating_status
 ORDER BY operating_status;

SELECT IF((SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'station'
              AND COLUMN_NAME = 'operating_status') = '3',
          'OK: v61 完成 —— 新建水站默认「待上线」（3）',
          'WARN: 默认值不是 3，请检查上面的输出') AS result;
