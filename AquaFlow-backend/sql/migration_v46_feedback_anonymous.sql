-- =============================================================================
-- V46: 客户反馈支持「匿名提交」（feedback 加 anonymous 列）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   mysql -uroot --default-character-set=utf8mb4 <库名> < migration_v46_feedback_anonymous.sql
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-18 产品裁定）
--
--   客户反馈此前**只有实名**一种形态：`feedback` 表只有
--   staff_id / customer_id / category / content / contact / create_time，
--   **没有任何匿名标记**。而站长端「客户反馈」页
--   （`miniapp-delivery/pages/station-mgmt/customer-feedback/`）会把客户姓名显示出来
--   （`FeedbackMapper.listCustomerFeedbackByStation` 已 `left join customer` 带出 `name`）。
--
--   于是出现一个真实矛盾：客户想报"某配送员态度差""水站乱收费"这类**针对水站本身**的问题时，
--   实名意味着**当着被投诉方的面投诉他**——结果就是这类问题根本不会被报上来。
--   产品裁定：**客户反馈要支持匿名提交**。
--
--   本迁移只补「落库能表达匿名」这一半；**真正的脱敏在 SQL 查询层**
--   （`FeedbackMapper.listCustomerFeedbackByStation` 用 CASE 置 NULL，见该 mapper 的注释），
--   前端拿不到姓名/客户号，也就无从显示。
--
-- -----------------------------------------------------------------------------
-- 语义（务必按这个口径读）
--   · anonymous = 0 = **实名**（默认值）。姓名 / 客户号照常对站长下发。
--   · anonymous = 1 = **匿名**。判据是「**站长不知道是谁**」，**不是**「前端不显示」：
--     对站长端的列表查询，`customer_id` 与 JOIN 出来的姓名**必须都由 SQL 置为 NULL**。
--     ⚠️ 只在 Java 里把返回值置空是**假的脱敏** —— 下一个人顺手把 `c.name` 加回 select，
--     或者换一个调用点（如新增导出接口）复用同一段 SQL，泄露就回来了，且没有任何编译期或
--     运行期提示。脱敏必须钉在**数据出库的那一句 SQL** 上。
--   · `content` / `category` / `contact` / `create_time` **照常在站长列表返回**：
--     匿名保护的是**身份**，不是**内容**——内容恰恰是站长要处理的东西。
--     `contact` 由客户自己选填：他填了就是**主动**留下联系方式（等于自认"这一条可以回我"），
--     不填则站长无从联系。这里不替客户做二次裁剪。
--   · **顾客自己的** `GET /api/feedback/my` **不受影响**：那是他自己的记录，`customer_id`
--     照常返回（`select *`），否则「我的反馈」会退化成一片空白。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 纯新增 1 个 NOT NULL DEFAULT 0 列，**不动任何存量列、不动任何存量行**：
--     存量反馈全部落到 0（实名），与升级前的可见性**完全一致**（不存在"升级后突然多出一批匿名记录"）。
--   · 不加索引：反馈量级是"每站每天几条"，站过滤走的是 customer_station_config / orders 的存在性子查询，
--     在 anonymous 上建索引没有选择度收益。将来真要做"只看匿名"的筛选再加。
--   · 对业务零影响：feedback 不参与计价 / 库存 / 对账 / 订单。
--
-- 幂等
--   · 加列先查 information_schema 再 PREPARE；已存在则打印 skip 并原样放行
--     → 二次执行不改任何东西（本列无存量回填，故没有"第二次影响 0 行"的 UPDATE 步骤）。
--
-- 回滚
--   · ALTER TABLE feedback DROP COLUMN anonymous;
--   · 回滚只丢"这条反馈是不是匿名提的"这一位信息；**已按匿名脱敏返回过的数据无法追回**，
--     且回滚后这些记录会**变回实名可见**（customer_id 一直在库里，脱敏发生在查询时）。
--     这正是"脱敏必须在 SQL 层"的代价与前提：库里始终留着真实 customer_id。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('feedback','customer','orders'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（feedback / customer / orders 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加列（NOT NULL DEFAULT 0：存量记录一律实名，与升级前可见性一致）
-- -----------------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'feedback' AND COLUMN_NAME = 'anonymous');
SET @ddl1 := IF(@has_col = 0,
                'ALTER TABLE feedback ADD COLUMN anonymous tinyint NOT NULL DEFAULT 0 COMMENT ''是否匿名(v46); 0=实名 1=匿名。判据是"站长不知道是谁"：站长端列表必须在 SQL 层把 customer_id 与姓名置 NULL，详见 FeedbackMapper'' AFTER contact',
                'SELECT ''skip: feedback.anonymous 已存在'' AS note');
PREPARE st1 FROM @ddl1;
EXECUTE st1;
DEALLOCATE PREPARE st1;

-- -----------------------------------------------------------------------------
-- 第 2 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：列定义（应为 tinyint NOT NULL DEFAULT 0）---' AS step;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'feedback' AND COLUMN_NAME = 'anonymous';

SELECT '--- 校验 B：匿名分布（升级后 anonymous=1 应为 0 行，除非此后有人真的匿名提过）---' AS step;
SELECT anonymous, COUNT(*) AS rows_cnt FROM feedback GROUP BY anonymous ORDER BY anonymous;

SELECT '--- 校验 C：列为 NOT NULL 且无 NULL 值？（都应成立）---' AS step;
SELECT COUNT(*) AS null_rows FROM feedback WHERE anonymous IS NULL;

SELECT 'V46 完成：反馈可表达匿名（0=实名 / 1=匿名）；脱敏发生在 FeedbackMapper 的查询 SQL 上' AS note;
