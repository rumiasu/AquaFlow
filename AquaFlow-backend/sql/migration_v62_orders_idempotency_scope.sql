-- =============================================================================
-- v62 · 下单幂等的作用域收口：唯一键改成 (customer_id, idempotency_key) + 请求摘要列
--
-- 依据：2026-09-25 架构评审报告 §5.5（问题 5，P1），用户 2026-09-25 裁定「剩下的全修」。
--
-- 问题（三条，都在当前代码里可复现）：
--   ① 唯一键是**单列** `UNIQUE KEY idx_orders_idempotency_key (idempotency_key)`，
--      而查询 `OrderMapper.findByIdempotencyKey(key)` 也是全局单列 ——
--      两个客户用同一个键时，会**把别人的订单 id 返回给调用者**（越权信息泄露）；
--   ② 客户端不传键时服务端自己生成 UUID ⇒ 每次重试都是新键 ⇒ 幂等形同虚设
--      （"断网后重试"正是幂等唯一的用途）；已改成**必传 + 跨重试复用**；
--   ③ "同键不同内容"没有任何冲突语义：客户端换个金额/商品用同一个键提交，
--      会拿回一张与本次请求无关的旧单，而它以为自己下单成功了。
--
-- 本迁移负责①②的**数据库那一半**：作用域必须与代码里的查询口径一致 ——
-- 只改代码不改键，第二个客户永远建不了单（撞旧唯一键）；
-- 只改键不改代码，跨客户复用键仍然返回别人的单。两处必须同时上。
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 加 1 列 `orders.request_digest`（varchar(64)，可空，只存 SHA-256 十六进制摘要）。
--     可空是**有意的**：升级前的存量订单没有摘要（判据见 OrderServiceImpl：
--     `requestDigest == null` 时只按键命中、不与本次请求比对，历史单因此不会被误判成"内容不同"）。
--   · 换 1 个唯一键：`idx_orders_idempotency_key(idempotency_key)` →
--     `uk_orders_idem_customer(customer_id, idempotency_key)`。
--     **不改任何金额、不改任何状态、不删数据**；没有 UPDATE / DELETE / INSERT。
--   · 为什么可以安全地换：旧键是 (idempotency_key) 上的唯一约束，比新键更严 ——
--     满足旧键的数据必然满足新键，故**不需要去重预检**（这条推理只对本方向成立；
--     将来若有人把作用域改窄，必须先查重）。
--   · 执行顺序无要求：代码侧对两种键形态都能工作（命中查询带上 customer_id，
--     旧键下也只是"跨客户同名键建不了单"，不会算错账）。**但仍建议代码先上**。
--
-- 幂等
--   · 加列 / 删键 / 加键各自先查 information_schema 再 PREPARE，已存在则打印 skip 原样放行；
--   · 三次重复执行结果一致；本脚本不含任何数据变更语句。
--
-- 回滚
--   · `ALTER TABLE orders ADD UNIQUE KEY idx_orders_idempotency_key (idempotency_key);`
--     然后 `ALTER TABLE orders DROP KEY uk_orders_idem_customer;`
--     ⚠️ 回滚前必须确认**没有**"不同客户使用同一个幂等键"的行，否则加旧键会失败
--     （查法：SELECT idempotency_key, COUNT(DISTINCT customer_id) c FROM orders
--            WHERE idempotency_key IS NOT NULL GROUP BY idempotency_key HAVING c > 1;）
--   · `request_digest` 列可留（可空、只读回显），要删就 `DROP COLUMN request_digest`。
--     回滚**不丢钱、不丢订单**：这一列只是"同键第二次请求的内容指纹"。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（orders 表不在就中止，避免在别的库上瞎改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders');
SELECT IF(@tbl_cnt = 1,
          'OK: 目标库校验通过（orders 在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（orders 命中 ', @tbl_cnt, '/1），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 1, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：加 request_digest 列（幂等请求摘要）
-- -----------------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'request_digest');
SET @sql := IF(@has_col = 0,
    'ALTER TABLE orders ADD COLUMN request_digest varchar(64) DEFAULT NULL COMMENT ''幂等请求摘要(SHA-256)：同键第二次请求用来判断内容是否一致；NULL=升级前的存量单'' AFTER idempotency_key',
    'SELECT ''skip: orders.request_digest 已存在'' AS note');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 2 步：删掉单列唯一键（它把"两个客户用同一个键"也判成冲突）
-- -----------------------------------------------------------------------------
SET @has_old := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders'
                   AND INDEX_NAME = 'idx_orders_idempotency_key');
SET @sql := IF(@has_old > 0,
    'ALTER TABLE orders DROP KEY idx_orders_idempotency_key',
    'SELECT ''skip: idx_orders_idempotency_key 不存在'' AS note');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 3 步：建 (customer_id, idempotency_key) 唯一键
--   ⚠️ 列顺序不能反：命中查询与"同客户才防重"的语义都是 customer 在前。
--   MySQL 唯一键对 NULL 不判重 ⇒ idempotency_key IS NULL 的存量行不受影响
--   （下单接口现在**强制**传键，新单不会再出现 NULL）。
-- -----------------------------------------------------------------------------
SET @has_new := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders'
                   AND INDEX_NAME = 'uk_orders_idem_customer');
SET @sql := IF(@has_new = 0,
    'ALTER TABLE orders ADD UNIQUE KEY uk_orders_idem_customer (customer_id, idempotency_key)',
    'SELECT ''skip: uk_orders_idem_customer 已存在'' AS note');
PREPARE st FROM @sql;
EXECUTE st;
DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 第 4 步：结果回读（人工核对用；不参与判定）
-- -----------------------------------------------------------------------------
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols,
       IF(NON_UNIQUE = 0, 'UNIQUE', 'NON-UNIQUE') AS kind
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'orders'
   AND INDEX_NAME IN ('idx_orders_idempotency_key', 'uk_orders_idem_customer')
 GROUP BY INDEX_NAME, NON_UNIQUE;
