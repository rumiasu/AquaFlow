-- =============================================================================
-- 库存预留迁移 · **只读状态探针**（不改任何数据；先跑它，再决定跑哪个迁移）
--
-- 依据：2026-09-25《库存二次收口验收结论》M2 —— "必须按**数据转换状态及转换证据**决定路径；
--       表存在、有两列不等于实物转换已完成"；以及《下一轮任务契约-迁移路径收尾》§3：
--       先产出只读分类表，再修操作卡；**禁止**继续写"所有状态都按 v65 → v63 → v64 安全执行"。
--
-- 用法（纯读，可以在备库先跑）：
--     mysql -uroot --default-character-set=utf8mb4 <库名> < sql/probe_inventory_reservation_state.sql
--
-- ⚠️ 实现注意：**不能用 `IF(@t_exists = 1, (SELECT ... 业务表 ...), 0)` 这种"短路"写法** ——
--    MySQL 会先把子查询解析/准备出来，表不存在时整条语句直接 1146（本脚本第一版就踩了）。
--    所以凡是要碰 `inventory_reservation` 的统计，都走 `PREPARE`：表不存在时换成常量赋值。
--
-- 判据（全是计数，不看"表在不在"这种弱信号）：
--   inflight                在途单（status 1/2）的明细数
--   cred_active             活跃凭据数
--   missing_cred            在途/已送达明细里**没有活跃凭据**的条数
--   restore_pending         在途明细里**一条凭据都没有**、`deducted_qty > 0`、且该单没有 v63 加回流水的条数
--                           （= 还需要恢复实物的明细；判据与 v63 完全一致，含"加回流水"那道护栏）
--   wrong_station           活跃凭据的站别 ≠ 该单当前履约站的条数（= 旧版 v63 留下的形状）
--   mirror_bad              在途/已送达明细的镜像 ≠ 活跃凭据预留量的条数
--   terminal_order_cred     ★ 终态单（已完成 4 / 已取消 5）上仍挂着的活跃凭据条数（v64 的 0 步会释放）
--   delivered_order_cred    ★ **已送达(3)** 单上仍挂着的活跃凭据条数（契约 R1：3 = 货已出库、只等收钱，
--                           本来就不该有活跃预留；有出库证据的由 v64 释放，拿不到证据的 v64 会中止）
--   stock_gap_pairs         ★ 存量差异：Σ活跃预留 > 在库实物的 (站,商品) 对数（**迁移不修**）
--   cred_without_inv_row    ★ 存量差异：活跃凭据挂在没有库存行的 (站,商品) 上（**迁移不修**）
--   need_mismatch           ★ **阻断项**（不再算"存量差异"）：预留量越界 / 快照与明细量不一致（契约 A2）
--                           —— 需求快照直接决定补位要分多少货，坏快照必须在 DML 之前人工修掉；
--                           表缺 need 两列时不判定（恒 0，改由 `need_snapshot_columns` 提示先跑 v65）
-- =============================================================================

SET @db := DATABASE();

SET @db_ok := (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('inventory','inventory_record','orders','order_item'));

SELECT IF(@db_ok = 4,
          'OK: 目标库校验通过（inventory / inventory_record / orders / order_item 均在）',
          '警告：这不像 aquaflow 库（缺关键表），先确认库名再继续') AS precheck;

SET @t_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation');
SET @need_cols := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                      AND COLUMN_NAME IN ('need_qty','need_time'));
SELECT @t_exists AS reservation_table, @need_cols AS need_snapshot_columns;

-- 统计（表不存在时全部记 0）
SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @inflight FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE o.status IN (1,2)',
  'SET @inflight := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @cred_active FROM inventory_reservation WHERE status = 1',
  'SET @cred_active := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @missing_cred FROM order_item oi JOIN orders o ON o.id = oi.order_id
    WHERE o.status IN (1,2) AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
      WHERE r.order_item_id = oi.id AND r.status = 1)',
  'SET @missing_cred := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @restore_pending FROM order_item oi JOIN orders o ON o.id = oi.order_id
    WHERE o.status IN (1,2) AND COALESCE(oi.deducted_qty,0) > 0
      AND NOT EXISTS (SELECT 1 FROM inventory_reservation r WHERE r.order_item_id = oi.id)
      AND NOT EXISTS (SELECT 1 FROM inventory_record ir WHERE ir.ref_id = oi.order_id
                        AND ir.note LIKE ''v63 库存预留模型迁移%'')',
  'SET @restore_pending := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
-- ↑ ⚠️ 最后那个 `NOT EXISTS(加回流水)` 必须与 v63 的判据一致（v63 用"该单有没有 v63 加回流水"
--    作为第二道防重复加回的护栏）。少了它，一个"手工删掉凭据行"的库会被误判成"还需恢复实物"，
--    而 v63 实际上会给它 restore=0（2026-09-25 文档一致性核查 P2-9）。

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @wrong_station FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
    WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id)',
  'SET @wrong_station := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @mirror_bad FROM order_item oi JOIN orders o ON o.id = oi.order_id
     LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
    WHERE o.status IN (1,2,3) AND COALESCE(oi.deducted_qty,0) <> COALESCE(r.reserved_qty,0)',
  'SET @mirror_bad := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ★ 终态单（已完成 4 / 已取消 5）上还挂着活跃凭据：v64 的 0 步会释放它们。
--   上一版探针没有这个计数 ⇒ 这类库会被判成"什么都没事"，而脚本的门禁却会拦下来（核查 P0-1）。
SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @terminal_cred FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
    WHERE r.status = 1 AND o.status IN (4,5)',
  'SET @terminal_cred := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ★ **已送达(3)** 单上还挂着活跃凭据（契约 R1）：3 = 货已出库、只等收钱，本来就不该有活跃预留。
--   v64 会按"出库证据"（CONSUME 流水 / 已出库凭据）释放它；拿不到证据的会中止并要求人工核对。
SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @delivered_cred FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
    WHERE r.status = 1 AND o.status = 3',
  'SET @delivered_cred := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ★ 存量账实差异（Σ预留>实物 / 凭据无库存行 / 快照与明细不一致）：**迁移不负责修**，
--   但要让操作人先知道"跑完这些差异还在"，而不是跑完才发现（核查 P1-5）。
SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @stock_gap FROM (
      SELECT r.station_id, r.product_id FROM inventory_reservation r
        JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id
       WHERE r.status = 1 GROUP BY r.station_id, r.product_id
      HAVING SUM(r.reserved_qty) > MAX(i.quantity)) a',
  'SET @stock_gap := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT COUNT(*) INTO @cred_no_inv FROM inventory_reservation r WHERE r.status = 1
     AND r.reserved_qty > 0
     AND NOT EXISTS (SELECT 1 FROM inventory i
                      WHERE i.station_id = r.station_id AND i.product_id = r.product_id)',
  'SET @cred_no_inv := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
-- ↑ `reserved_qty > 0` 是必须的：外派到"没配这个商品"的站是**合法动作**（那边可用量 0 ⇒ 凭据 reserved=0
--    = 缺货待补），只按"有没有库存行"判会把合法状态算成差异。

-- ⚠️ `@need_cols = 2` 这个额外条件是**必须的**：`PREPARE` 会当场解析并校验列名，
--    表里没有 `need_qty` 时整条语句直接 1054 —— 而那正是"跑过上一版 v63、还需要先跑 v65"的库，
--    也就是最需要探针给路由的一类库（本脚本第一版就在这里整段挂掉，探针什么 ROUTE 都打不出来）。
SET @s := IF(@t_exists = 1 AND @need_cols = 2,
  'SELECT COUNT(*) INTO @need_mismatch FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id
    WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity)',
  'SET @need_mismatch := 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT @inflight AS inflight, @cred_active AS cred_active, @missing_cred AS missing_cred,
       @restore_pending AS restore_pending, @wrong_station AS wrong_station, @mirror_bad AS mirror_bad,
       @terminal_cred AS terminal_order_cred, @delivered_cred AS delivered_order_cred,
       @stock_gap AS stock_gap_pairs,
       @cred_no_inv AS cred_without_inv_row, @need_mismatch AS need_mismatch;

-- -----------------------------------------------------------------------------
-- 分类与路由（**这就是操作卡的依据**）
--   ⚠️ 四条贯穿所有分支的前提（2026-09-25 文档一致性核查 P1-4 / Q1-10；契约 A2 加了第四条）：
--     · 库名没过校验（缺关键表）⇒ **先确认库名**，不给任何可执行路径；
--     · `need_snapshot_columns < 2` ⇒ **先跑 v65**（v63/v64 都会因缺列直接 ABORT）；
--     · **`need_mismatch > 0` ⇒ 两个脚本都会在 DML 之前拒绝**（契约 A2：坏快照会被当成"这条要多少桶"
--       而扩大错误预留）⇒ 先人工把快照改回真相源 `order_item.quantity`，再谈跑哪个脚本；
--     · 存量账实差异（stock_gap_pairs / cred_without_inv_row）**迁移不修**，
--       跑完仍然在，交给对账 E11/E15/E16 —— 门禁只拦"迁移新造的"，且不允许**变大**。
-- -----------------------------------------------------------------------------
SET @v65_first := IF(@t_exists = 1 AND @need_cols < 2, ' （★本库缺 need 两列：先跑 v65）', '');
SET @legacy_diffs := IF(@stock_gap + @cred_no_inv > 0,
                        CONCAT('（注意：本库有存量账实差异 stock_gap=', @stock_gap,
                               ' / cred_no_inv=', @cred_no_inv,
                               '，迁移**不修**它们、但也不会让它们变大，跑完仍在，交给对账 E11/E15/E16）'), '');

SELECT CASE
  WHEN @db_ok < 4 THEN
    '状态? · 库名未通过校验（这不像 aquaflow 库）'
  WHEN @t_exists = 0 THEN
    '状态1 · 从未转换（连凭据表都没有）'
  WHEN @restore_pending > 0 AND @wrong_station > 0 THEN
    '状态5-D · 混合且异常（既有错站凭据、又有待恢复实物的明细）'
  WHEN @need_mismatch > 0 THEN
    '状态6 · 需求快照异常（坏快照 / 预留越界 ⇒ 两个脚本都会在 DML 之前拒绝）'
  WHEN @restore_pending > 0 THEN
    '状态5-M · 混合转换（有凭据，也有还需要恢复实物的老明细）'
  WHEN @wrong_station > 0 OR @terminal_cred > 0 OR @delivered_cred > 0 THEN
    '状态2/3 · 旧版 v63 已执行或 R1 形状（有错站凭据 和/或 已送达、终态单上仍挂活跃凭据）'
  WHEN @missing_cred > 0 THEN
    '状态4-P · 部分转换（还有在途明细没有凭据，但没有待恢复实物）'
  WHEN @mirror_bad > 0 THEN
    '状态4-M · 已转换但镜像漂移（明细镜像 ≠ 活跃凭据预留量）'
  ELSE
    '状态4-OK · 已转换完成（无待恢复实物、无坏快照、无错站、无缺凭据、无镜像漂移，已送达/终态单无活跃凭据）'
END AS state;

SELECT CASE
  WHEN @db_ok < 4 THEN
    'ROUTE：**先确认库名，不要执行任何迁移**（缺 inventory / inventory_record / orders / order_item 中的表；v63/v64 在同样条件下也会在第 0 步预检中止）。'
  WHEN @t_exists = 0 THEN
    CONCAT('ROUTE：执行 v63 →（可选）v64 验空操作。v63 会建表并自带 need 两列，不需要单独跑 v65。', @legacy_diffs)
  WHEN @restore_pending > 0 AND @wrong_station > 0 THEN
    CONCAT('ROUTE：**停在预检，不要自动执行任何迁移**。两种脚本都不安全：v63 会被错站门禁拒绝、v64 不会恢复实物。先人工核对下面列出的明细（很可能是被手工删过凭据行）。', @legacy_diffs)
  WHEN @need_mismatch > 0 THEN
    CONCAT('ROUTE：**停在预检，不要执行 v63/v64**。本库有 ', @need_mismatch,
           ' 条凭据的需求快照与明细量不一致（或预留越界）—— 补位就是按这个快照分货的，',
           '坏快照会把错误预留放大（演练：真实需求 2、快照 10 ⇒ 预留被补到 10、可用量归 0，而门禁看不出来）。',
           '先按下表把 need_qty 改回真相源（= 对应 order_item.quantity；need_time = orders.create_time），',
           '再重新跑本探针确认 need_mismatch = 0。', @v65_first, @legacy_diffs)
  WHEN @restore_pending > 0 THEN
    CONCAT('ROUTE：执行 v63（恢复实物 + 建凭据）→ 再执行 v64（通常空操作）。', @v65_first, @legacy_diffs)
  WHEN @wrong_station > 0 OR @terminal_cred > 0 OR @delivered_cred > 0 THEN
    CONCAT('ROUTE：**不要先跑 v63**（它会被错站/不该有的活跃预留挡下并中止）。路径 =', @v65_first,
           ' v64 修错站/补漏/补位，释放终态单(4/5)与已送达单(3)上不该有的活跃凭据（会打印清单；',
           '已送达但拿不到出库证据的会中止并要求人工核对）→ 之后可按需跑 v63 验空操作。', @legacy_diffs)
  WHEN @missing_cred > 0 THEN
    CONCAT('ROUTE：执行 v63（补凭据、无实物可恢复）→ 再执行 v64（通常空操作）。', @v65_first, @legacy_diffs)
  WHEN @mirror_bad > 0 THEN
    CONCAT('ROUTE：镜像与活跃凭据不一致 ⇒ 跑 v63（它会把全部在途/已送达单的镜像重写成活跃凭据的预留量）→ 再跑 v64 验空操作。', @v65_first, @legacy_diffs)
  ELSE
    CONCAT('ROUTE：已转换完成。需要时可跑 v63 + v64 各一遍验"零变化"（幂等）；有合法待补位时 v63 会顺带补位并同步镜像，所以"跑完指纹一模一样"不是必然，**"没有新造差异"（newly_created_violations = 0）才是判据**。', @v65_first, @legacy_diffs)
END AS route;

-- -----------------------------------------------------------------------------
-- 需要人工核对时（状态5-D）的待核明细 / 错站凭据清单（表不存在时跳过，不报错）
-- -----------------------------------------------------------------------------
SET @s := IF(@t_exists = 1,
  'SELECT oi.id AS order_item_id, oi.order_id, o.status AS order_status,
          o.station_id AS owner_station, o.delivery_station_id, oi.quantity, oi.deducted_qty,
          (SELECT COUNT(*) FROM inventory_reservation r WHERE r.order_item_id = oi.id) AS any_credentials
     FROM order_item oi JOIN orders o ON o.id = oi.order_id
    WHERE o.status IN (1,2) AND COALESCE(oi.deducted_qty,0) > 0
      AND NOT EXISTS (SELECT 1 FROM inventory_reservation r WHERE r.order_item_id = oi.id)
      AND @wrong_station > 0
    ORDER BY oi.id LIMIT 50',
  'SELECT ''(缺 inventory_reservation 表：本库尚未迁移，无需核对明细)'' AS note');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF(@t_exists = 1,
  'SELECT r.id AS reservation_id, r.order_id, r.order_item_id,
          r.station_id AS credential_station, COALESCE(o.delivery_station_id, o.station_id) AS should_be_station
     FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
    WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id)
    ORDER BY r.id LIMIT 50',
  'SELECT ''(表不存在，无需核对)'' AS note');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 不该有活跃预留的单（已送达 3 / 已完成 4 / 已取消 5）上仍挂活跃凭据的清单
-- （v64 的 0 步会释放它们；已送达的还要先看有没有出库证据 —— v64 会自己判定并打印）
-- ⚠️ `need_qty` 只在表里确实有这两列时才 select（缺列时直接引用会 1054，见上面 need_mismatch 的注释）。
SET @need_sel := IF(@need_cols = 2, ', r.need_qty', '');
SET @s := IF(@t_exists = 1,
  CONCAT('SELECT r.id AS reservation_id, r.order_id, o.status AS order_status, r.order_item_id,
          r.station_id, r.reserved_qty', @need_sel, '
     FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
    WHERE r.status = 1 AND o.status IN (3, 4, 5)
    ORDER BY o.status, r.order_id LIMIT 50'),
  'SELECT ''(表不存在，无需核对)'' AS note');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 需求快照异常（阻断项，状态6）的清单：先把这些行的 need_qty 改回真相源再跑迁移
-- 真相源：`need_qty = order_item.quantity`、`need_time = orders.create_time`（见 docs/design/28 §11.9）
SET @s := IF(@t_exists = 1 AND @need_cols = 2,
  'SELECT r.id AS reservation_id, r.order_id, r.order_item_id, r.station_id, r.product_id,
          r.need_qty, oi.quantity AS item_quantity, r.reserved_qty,
          CONCAT(''UPDATE inventory_reservation SET need_qty = '', oi.quantity,
                 '' WHERE id = '', r.id, '';  -- 修完请重跑本探针'') AS fix_sql
     FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id
    WHERE r.status = 1
      AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity)
    ORDER BY r.id LIMIT 50',
  'SELECT ''(表不存在或缺 need 两列：本库还轮不到"快照异常"这一步，先按上面的 need_snapshot_columns 处理)'' AS note');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
