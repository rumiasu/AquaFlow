-- =============================================================================
-- v64 · 首版 v63 的补偿/升级（已执行过首版 v63 的库专用）
--
-- 依据：2026-09-25 交付验收意见 R2 / R3 / R5，与返工契约 §6 的
--       「已执行过的库必须考虑补偿/升级，不能只悄悄改脚本后说完成」。
--
-- 首版 v63 已经跑过的库，数据里有三处与当前模型不符 —— 本脚本一次修完：
--   ① **凭据挂错站**：首版把新凭据的站别写成 `orders.station_id`（归属站），
--      而它应当是**当前履约站** `coalesce(o.delivery_station_id, o.station_id)`。
--      后果：跨站外派的在途单，其凭据挂在归属站 ⇒ 接单站完成配送时被
--      `shipForOrder(orderId, 履约站)` 以"凭据站别不符"拒绝 ⇒ **单卡死**。
--      本脚本把这类凭据置为已释放，并在履约站重建一份（保留旧行当审计轨迹，
--      形状与运行时的 `transferForOrder` 完全一致）。
--   ② **漏凭据**：首版只处理 `deducted_qty > 0` 的明细，下单时全缺货
--      （`deducted_qty = 0`）的明细**没有凭据** ⇒ 完成配送的覆盖检查
--      （明细数 == 活跃凭据数）必然失败。本脚本给每条在途明细补齐凭据。
--   ③ **镜像没同步**：`order_item.deducted_qty` 的唯一语义是"当前活跃凭据的预留量镜像"
--      （返工 R5），首版只加回实物、没改这一列。本脚本按活跃凭据重写**在途单**的镜像。
--      已完成/已取消的历史行**刻意不动**：那是死数据，没有任何代码读它，
--      在补偿脚本里顺手改写历史只会扩大风险面。
--
-- 与 v63 的关系（**先看这张表再决定跑哪个**）：
--   · 还没跑过任何 v63 的库   → 只跑**返工后的** `migration_v63_inventory_reservation.sql`，
--                              不要跑本脚本（它是空操作，但没必要）。
--   · 跑过**首版** v63 的库    → 跑本脚本（它把 ①②③ 一次修完）。
--   · 跑过**返工后** v63 的库  → 本脚本是**幂等空操作**（跑一遍能自证：各项计数全 0）。
--
-- -----------------------------------------------------------------------------
-- 做法（一个事务里七步；计划与**基线**都用临时表先算好，不边算边改）
--   0) **释放终态单上的活跃凭据**（`o.status IN (4,5)` ⇒ `status=3` + `released_qty` 留痕）：
--      这类行既不该存在，也不是"搬站"能修的；唯一非破坏性修法是释放（不动实物、不造库存）。
--      ⚠️ 缺了这一步就会**死路**：上一版的搬迁计划只覆盖在途单，而门禁④不带状态过滤 ⇒
--      终态单上的错站凭据让门禁失败、整笔回滚，而所有文档都把人指向 v64（2026-09-25 核查 P0-1）。
--   1) 释放挂错站的活跃凭据（`status=3`、`released_qty = reserved_qty` 留痕）—— 覆盖在途/已送达(1,2,3)
--   2) 在**当前履约站**重建一条 `reserved_qty = 0` 的活跃凭据（先占位，由第 4 步分配）
--   3) 给在途/已送达单里**没有活跃凭据**的明细补一条 `reserved_qty = 0` 的凭据
--   3b) 补齐这两批凭据的 `need_qty` / `need_time` 快照（v65 的列；老库上可能是 NULL）
--   4) **补位**：把每个 (站,商品) 的闲货（实物 − **Σ全部活跃预留**）按凭据上的
--      `need_time`（下单时间快照）先来先得分给"有缺口"的活跃凭据 —— 与运行时的
--      `backfillReservations` 同一口径（排序依据是**业务需求时间**，不是凭据 id：
--      换站会新建凭据、id 变了但需求时间没变）
--   5) 同步在途/已送达单的 `order_item.deducted_qty` 镜像
--   6) **提交前不变量门禁**：七条不变量（其中"存量差异"按 0 步之前的**基线**排除）任一条不满足 ⇒ 不 COMMIT
--   ⚠️ 第 1、2 步刻意"先释放后重建"：唯一键 `uk_reservation_active_item` 只允许
--      一条活跃凭据，顺序反了会撞 1062 并整笔回滚。
--   ⚠️ 第 4 步只**增加**预留量，绝不减少：已承诺给客户的数量不能在修复脚本里被收回
--      （那会让本来能完成的单突然完不成）。所以"实物不够"的库不会被本脚本"修平"，
--      而是留出差异给对账 E11/SE7 去报（见文末自查）——**门禁也按同一口径**：
--      迁移前就存在的 Σ预留>实物 / 凭据无库存行不拦（存量问题），**新造的必拦**。
--
-- 执行方式（**硬要求**）
--   · 用**出错即停止**的客户端（mysql CLI 的默认行为）执行，**绝对不要**加 `--force`；
--     SQL 语句失败**不自动**等价于整个事务回滚 —— 本脚本靠"客户端出错即断开" +
--     "把 COMMIT 本身做成条件语句"两条一起兜底（不变量不满足时执行的不是 COMMIT）。
--   · ⚠️ **退出码只有在不加 `--force` 时才可信**（v63 演练实测：`--force` 下报错也会返回 0）。
--   · 执行前：停止业务写入 + `mysqldump` 到 `backup/`。
--   · 需要 `need_qty` / `need_time` 两列（v65 加的）：表里没有就先跑
--     `migration_v65_reservation_need_snapshot.sql`（第 0 步会预检并给出提示）。
--   · **v64 必须排在 v63 之后**（它假设表已存在且列齐全）。
--
-- 执行顺序 / 幂等 / 原子性
--   · **代码先上**（新模型不认旧口径）。
--   · 六步全在**一个事务**里：任意一步失败/客户端断开 ⇒ 整体回滚，重跑结果与成功一次相同。
--   · 幂等：重跑时第 1 步（挂错站的活跃凭据）与第 3 步（无凭据的在途明细）都查不到行；
--     第 4 步此时 `free = 实物 − Σ预留 ≤ 0`（没有闲货）或无缺口 ⇒ 更新 0 行。
--
-- 回滚
--   · 结构未变、只改了 `inventory_reservation` 与 `order_item.deducted_qty` 两类行，
--     没有"反向脚本"——用执行前的 `mysqldump` 恢复即可（这也是要求先备份的原因）。
--   · ⚠️ **不要**手工把已释放的旧凭据改回 `status=1`：那会与新建的那条撞唯一键，
--     而且会让同一份货在新旧两站各被承诺一次。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（表不在就中止）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('inventory','inventory_reservation','orders','order_item'));
SET @res_cols := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                    AND COLUMN_NAME IN ('need_qty','need_time'));
SELECT IF(@tbl_cnt = 4 AND @res_cols = 2,
          'OK: 目标库校验通过（关键表均在，且凭据表带 need_qty/need_time）',
          CONCAT('ABORT: 关键表命中 ', @tbl_cnt, '/4、need 快照列命中 ', @res_cols, '/2',
                 ' —— 缺列请先执行 migration_v65_reservation_need_snapshot.sql')) AS precheck;

SET @abort := IF(@tbl_cnt <> 4 OR @res_cols <> 2,
                 'SELECT * FROM __ABORT_V64_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：算计划（三张临时表，只读不改数据）
--   ⚠️ MySQL 不允许多次引用同一张**临时**表，所以"缺失 / 搬迁 / 补位计划"各存一张。
-- -----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_missing`;
CREATE TEMPORARY TABLE `tmp_v64_missing` AS
SELECT oi.id                                   AS order_item_id,
       oi.order_id                             AS order_id,
       oi.product_id                           AS product_id,
       COALESCE(o.delivery_station_id, o.station_id) AS cred_station
  FROM order_item oi
  JOIN orders o ON o.id = oi.order_id
 WHERE o.status IN (1, 2)          -- ★ **只补在途单**：已送达(3) 是"货已出库、只等收钱"（契约 R1）
   AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
                    WHERE r.order_item_id = oi.id AND r.status = 1);
-- ↑ 为什么**不能**把 3 放进这里（上一版就是这么错的，2026-09-25 契约 R1）：
--   完成配送时 `OrderWorkflowServiceImpl.completeDelivery` 先调 `shipForOrder`（实物已减、凭据置为已出库），
--   现金未收时订单停在**已送达(3)**（`:572`），之后再收款只做 3→4（`:345-346`），**不会再出库**。
--   所以"状态 3 没有活跃凭据"是**正确状态**，给它补一条活跃凭据 = 把已经送出门的货又占住一次。
--   演练实测（`docs/audit/drill/drill_r1_delivered_cash_unpaid.sql`）：实物 8、已送达单 2 桶
--   ⇒ 上一版 v64 补出 ACTIVE(reserved=2)，可用量 8 → **6**，且后续 3→4 收款不会清掉它。

DROP TEMPORARY TABLE IF EXISTS `tmp_v64_move`;
CREATE TEMPORARY TABLE `tmp_v64_move` AS
SELECT r.id                                    AS old_id,
       r.order_id                              AS order_id,
       r.order_item_id                         AS order_item_id,
       r.product_id                            AS product_id,
       COALESCE(o.delivery_station_id, o.station_id) AS to_station,
       r.station_id                            AS from_station
  FROM inventory_reservation r
  JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1
   AND o.status IN (1, 2)          -- ★ 同上：活跃凭据只属于在途单；已送达/终态单上的活跃凭据归 2-0 释放
   AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id);

-- ★★ 2-0) **不该有活跃预留的单**上还挂着活跃凭据（status 1）：
--      · **已完成(4) / 已取消(5)**：这类行既不该有活跃预留，也不是"搬站"能修的（它压根不该存在）。
--      · **已送达(3)**：货已经出库（`completeDelivery` → `shipForOrder` 已减实物、凭据已置为已出库），
--        订单只是**还没收到钱**才停在 3；它同样不该有活跃预留（契约 R1）。
--     唯一非破坏性的修法是**释放**（status=3、released_qty=reserved_qty 留痕）：
--       · 不会重复扣实物（本脚本不动 inventory）；
--       · 不会凭空造库存（释放 ≠ 回补）；
--       · 让"活跃凭据只属于在途单(1,2)"这条不变量重新成立。
--     ⚠️ 为什么必须修（2026-09-25 文档一致性核查 P0-1）：v64 的**搬迁计划**只覆盖在途单，
--     而上一版的**门禁④**却不带状态过滤 —— 于是"终态单上的错站凭据"会让门禁失败、整笔回滚，
--     而所有文档都把人指向 v64 ⇒ **死路**。现在两边对齐：计划负责释放它，门禁才可能通过。
--     ⚠️ **已送达(3) 必须先拿到"确实出过库"的证据**（契约 R1 的"按履约/库存证据处理"）：
--       证据 = 该单有 `inventory_record` 的 CONSUME 流水（`ref_id = order_id`，`shipForOrder` 写的）
--              **或** 该明细存在一条**已出库(2)** 的凭据。
--       拿不到证据（订单说送达了、凭据说还没出库）⇒ **不能猜**：DML 之前中止并列明细（见 2-0b）。
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_terminal_cred`;
CREATE TEMPORARY TABLE `tmp_v64_terminal_cred` AS
SELECT r.id AS cred_id, r.order_id, r.order_item_id, r.station_id, r.reserved_qty, o.status AS order_status
  FROM inventory_reservation r
  JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND o.status IN (4, 5);

-- 已送达(3) 且**有出库证据**的活跃凭据 ⇒ 释放
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_delivered_cred`;
CREATE TEMPORARY TABLE `tmp_v64_delivered_cred` AS
SELECT r.id AS cred_id, r.order_id, r.order_item_id, r.station_id, r.reserved_qty, o.status AS order_status
  FROM inventory_reservation r
  JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND o.status = 3
   AND (EXISTS (SELECT 1 FROM inventory_record ir
                 WHERE ir.ref_id = r.order_id AND ir.type = 'CONSUME')
     OR EXISTS (SELECT 1 FROM inventory_reservation r2
                 WHERE r2.order_item_id = r.order_item_id AND r2.status = 2));

-- 已送达(3) 但**拿不到出库证据**的活跃凭据 ⇒ 人工核对（本轮不猜、不伪造出库）
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_delivered_cred_unproven`;
CREATE TEMPORARY TABLE `tmp_v64_delivered_cred_unproven` AS
SELECT r.id AS cred_id, r.order_id, r.order_item_id, r.station_id, r.reserved_qty, r.need_qty
  FROM inventory_reservation r
  JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND o.status = 3
   AND NOT EXISTS (SELECT 1 FROM inventory_record ir
                    WHERE ir.ref_id = r.order_id AND ir.type = 'CONSUME')
   AND NOT EXISTS (SELECT 1 FROM inventory_reservation r2
                    WHERE r2.order_item_id = r.order_item_id AND r2.status = 2);

SELECT (SELECT COUNT(*) FROM `tmp_v64_move`)                  AS plan_move_wrong_station,
       (SELECT COUNT(*) FROM `tmp_v64_missing`)               AS plan_insert_missing,
       (SELECT COUNT(*) FROM `tmp_v64_terminal_cred`)         AS plan_release_on_terminal_orders,
       (SELECT COUNT(*) FROM `tmp_v64_delivered_cred`)        AS plan_release_on_delivered_with_evidence,
       (SELECT COUNT(*) FROM `tmp_v64_delivered_cred_unproven`) AS blocked_delivered_without_evidence;

-- 待释放清单（打出来给操作人看，别让它无声无息地发生）
SELECT cred_id, order_id, order_item_id, station_id, reserved_qty, order_status
  FROM `tmp_v64_terminal_cred` ORDER BY cred_id LIMIT 50;
SELECT cred_id, order_id, order_item_id, station_id, reserved_qty, order_status
  FROM `tmp_v64_delivered_cred` ORDER BY cred_id LIMIT 50;
-- 拿不到出库证据的清单（要人工核对；不出意外的话是空的）
SELECT cred_id, order_id, order_item_id, station_id, reserved_qty, need_qty
  FROM `tmp_v64_delivered_cred_unproven` ORDER BY cred_id LIMIT 50;

-- 已送达单上的活跃凭据**没有出库证据** ⇒ 无法判定该释放还是该恢复，DML 之前中止
SET @abort_unproven := IF((SELECT COUNT(*) FROM `tmp_v64_delivered_cred_unproven`) > 0,
                          'SELECT * FROM __ABORT_V64_DELIVERED_ACTIVE_WITHOUT_SHIP_EVIDENCE__', 'SELECT 1');
PREPARE st_abort_unproven FROM @abort_unproven;
EXECUTE st_abort_unproven;
DEALLOCATE PREPARE st_abort_unproven;

-- ★★ 基线（**必须在 DML 之前采集**，否则它就不是"迁移前"了 —— 第一版把它写在门禁旁边，
--    结果 before == after、门禁永远为 0，等于把门禁废掉）：
--    三类**迁移前就存在**的账实差异（本脚本只做等量搬运/释放/建凭据，不负责修它们），
--    门禁只拦"本脚本新造出来的"。存量差异照样打印，交给对账 E11/E15/E16。
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_viol_before`;
CREATE TEMPORARY TABLE `tmp_v64_viol_before` AS
SELECT CONCAT('RESERVED_GT_STOCK:', r.station_id, ':', r.product_id) AS viol_key,
       CAST(SUM(r.reserved_qty) - MAX(i.quantity) AS SIGNED) AS magnitude
  FROM inventory_reservation r
  JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id
 WHERE r.status = 1
 GROUP BY r.station_id, r.product_id
HAVING SUM(r.reserved_qty) > MAX(i.quantity)
UNION ALL
SELECT CONCAT('CRED_NO_INV:', r.id), r.reserved_qty
  FROM inventory_reservation r
 WHERE r.status = 1 AND r.reserved_qty > 0
   AND NOT EXISTS (SELECT 1 FROM inventory i
                    WHERE i.station_id = r.station_id AND i.product_id = r.product_id)
UNION ALL
SELECT CONCAT('NEED_MISMATCH:', r.id), r.reserved_qty
  FROM inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
 WHERE r.status = 1
   AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity);

SELECT COUNT(*) AS preexisting_diffs_left_to_reconcile FROM `tmp_v64_viol_before`;
-- ↑ >0 不代表脚本会失败：这些是**迁移前就有的**账实差异，跑完仍然在，由对账报给运维。
--   ⚠️ 但"还在"只允许**不变大** —— 门禁会比较每个键的 `magnitude`（契约 A2/R2）。

-- ★★ 2-0c) **脏需求快照 → DML 之前直接拒绝**（契约 A2 / 验收 R2）
--     需求快照（`need_qty`）是补位分配的依据，坏快照会被当成"这条要 10 桶"而**扩大错误预留**。
--     演练实测（`docs/audit/drill/drill_r2_dirty_need_snapshot.sql`）：实物 10、真实需求 2、预留 2、
--     坏快照 10 ⇒ 上一版把预留补到 10、可用量归 0，而差异键没变、门禁看不出。
--     与"历史账实差额"分开对待：差额不参与本次分配（可以留给对账），坏快照直接决定分配多少（不能放行）。
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_dirty_need`;
CREATE TEMPORARY TABLE `tmp_v64_dirty_need` AS
SELECT r.id AS reservation_id, r.order_id, r.order_item_id, r.station_id, r.product_id,
       r.need_qty, oi.quantity AS item_quantity, r.reserved_qty
  FROM inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
 WHERE r.status = 1
   AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity);

SELECT COUNT(*) AS dirty_need_snapshot FROM `tmp_v64_dirty_need`;
SELECT reservation_id, order_id, order_item_id, station_id, product_id,
       need_qty, item_quantity, reserved_qty
  FROM `tmp_v64_dirty_need` ORDER BY reservation_id LIMIT 50;

SET @abort_dirty64 := IF((SELECT COUNT(*) FROM `tmp_v64_dirty_need`) > 0,
                         'SELECT * FROM __ABORT_V64_DIRTY_NEED_SNAPSHOT__', 'SELECT 1');
PREPARE st_abort_dirty64 FROM @abort_dirty64;
EXECUTE st_abort_dirty64;
DEALLOCATE PREPARE st_abort_dirty64;

-- -----------------------------------------------------------------------------
-- 第 2 步：一个事务里完成修复
-- -----------------------------------------------------------------------------
START TRANSACTION;

-- 2-0) 先释放**不该有活跃预留的单**上的凭据（终态 4/5 + 已送达 3 且有出库证据；见上面 2-0 的说明）
--      ⚠️ MySQL 不允许在同一条语句里两次引用同一张临时表，所以两张清单用 UNION ALL 合成一个子查询。
UPDATE inventory_reservation r
  JOIN (SELECT cred_id FROM `tmp_v64_terminal_cred`
        UNION ALL
        SELECT cred_id FROM `tmp_v64_delivered_cred`) t ON t.cred_id = r.id
   SET r.status = 3,
       r.released_qty = r.reserved_qty,
       r.update_time = NOW()
 WHERE r.status = 1;

-- 2-1) 释放挂错站的活跃凭据（保留旧行当审计轨迹）
UPDATE inventory_reservation r
  JOIN `tmp_v64_move` m ON m.old_id = r.id
   SET r.status = 3,
       r.released_qty = r.reserved_qty,
       r.update_time = NOW()
 WHERE r.status = 1;

-- 2-2) 在**当前履约站**重建活跃凭据（先记 0，由 2-4 分配；顺序不能与 2-1 对调）
INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id,
                                  need_qty, need_time, reserved_qty, shipped_qty, released_qty,
                                  status, create_time, update_time)
SELECT m.order_id, m.order_item_id, m.product_id, m.to_station,
       oi.quantity, o.create_time, 0, 0, 0, 1, NOW(), NOW()
  FROM `tmp_v64_move` m
  JOIN order_item oi ON oi.id = m.order_item_id
  JOIN orders o ON o.id = m.order_id;

-- 2-3) 给在途单里没有活跃凭据的明细补齐凭据（缺货待补就记 0 —— 它就是"这条需求要货"的凭据）
INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id,
                                  need_qty, need_time, reserved_qty, shipped_qty, released_qty,
                                  status, create_time, update_time)
SELECT m.order_id, m.order_item_id, m.product_id, m.cred_station,
       oi.quantity, o.create_time, 0, 0, 0, 1, NOW(), NOW()
  FROM `tmp_v64_missing` m
  JOIN order_item oi ON oi.id = m.order_item_id
  JOIN orders o ON o.id = m.order_id;

-- 2-3b) 老库上补出的 need 快照可能为 NULL（本表刚由 v65 加列）：统一补齐，别让补位读到空
UPDATE inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
  JOIN orders o ON o.id = r.order_id
   SET r.need_qty = COALESCE(r.need_qty, oi.quantity),
       r.need_time = COALESCE(r.need_time, o.create_time)
 WHERE r.status = 1 AND (r.need_qty IS NULL OR r.need_time IS NULL);

-- 2-4) 补位：闲货按"业务需求时间"先来先得补给有缺口的活跃凭据
--      需求量与排序**只读凭据行的快照**（need_qty / need_time，v65）—— 不再 join order_item/orders：
--      二次验收 B2 的反例正是"当前读看得到新凭据、普通读看不到同一批提交里刚插的明细"。
--      前缀和用的是**缺口**（需求量 − 已预留）而不是"已分配量"：
--      一旦某条被 clamp 到闲货上限，闲货就已经分完了，后面的必然拿 0 ⇒ 与贪心逐个分配等价。
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_topup`;
CREATE TEMPORARY TABLE `tmp_v64_topup` AS
SELECT x.id, LEAST(x.deficit, GREATEST(0, x.free_qty - x.deficit_before)) AS add_qty
  FROM (SELECT r.id,
               (r.need_qty - r.reserved_qty) AS deficit,
               (COALESCE(inv.quantity, 0) - COALESCE(res.reserved_sum, 0)) AS free_qty,
               COALESCE(SUM(r.need_qty - r.reserved_qty) OVER (
                        PARTITION BY r.station_id, r.product_id
                        ORDER BY r.need_time, r.order_item_id
                        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS deficit_before
          FROM inventory_reservation r
          LEFT JOIN inventory inv
                 ON inv.station_id = r.station_id AND inv.product_id = r.product_id
          LEFT JOIN (SELECT station_id, product_id, SUM(reserved_qty) AS reserved_sum
                       FROM inventory_reservation WHERE status = 1
                      GROUP BY station_id, product_id) res
                 ON res.station_id = r.station_id AND res.product_id = r.product_id
         WHERE r.status = 1 AND r.need_qty > r.reserved_qty) x;

UPDATE inventory_reservation r
  JOIN `tmp_v64_topup` t ON t.id = r.id
   SET r.reserved_qty = r.reserved_qty + t.add_qty,
       r.update_time = NOW()
 WHERE r.status = 1 AND t.add_qty > 0;

-- 2-5) 同步在途单的 `order_item.deducted_qty` 镜像（= 活跃凭据的 reserved_qty；无凭据为 0）
--      ⚠️ 含"已送达(3)"：**它的镜像必须是 0**（货已出库、没有活跃凭据）。老库里可能留着旧值；
--      而且 2-0 刚释放过一批不该有的活跃凭据，镜像必须跟着归 0，否则门禁⑥会报差异、整笔回滚。
--      ⚠️ 2-0 释放过的**终态单**也要同步（其余历史行仍然刻意不碰）。
UPDATE order_item oi
  JOIN orders o ON o.id = oi.order_id
  LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
   SET oi.deducted_qty = COALESCE(r.reserved_qty, 0)
 WHERE o.status IN (1, 2, 3)
    OR o.id IN (SELECT order_id FROM `tmp_v64_terminal_cred`);

-- 2-6) **提交前不变量门禁**（与 v63 同一套判据）：不满足 ⇒ 本事务不 COMMIT，报错回滚。
--      不能"先 COMMIT 再 SELECT 出差异还退出 0"（二次收口契约 §2 B3 最后一条）。
--      ⚠️ 判据是"**本脚本新造出来的**差异"：迁移前就存在的账实差异属于存量问题（交给对账
--      E11/E15/E16），上一版把存量差异也拦下来 ⇒ 与脚本头"差额留给对账"的承诺相反，且会把
--      合法迁移拦死（2026-09-25 文档一致性核查 P0-2）。
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_viol_after`;
CREATE TEMPORARY TABLE `tmp_v64_viol_after` AS
SELECT CONCAT('RESERVED_GT_STOCK:', r.station_id, ':', r.product_id) AS viol_key,
       CAST(SUM(r.reserved_qty) - MAX(i.quantity) AS SIGNED) AS magnitude
  FROM inventory_reservation r
  JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id
 WHERE r.status = 1
 GROUP BY r.station_id, r.product_id
HAVING SUM(r.reserved_qty) > MAX(i.quantity)
UNION ALL
SELECT CONCAT('CRED_NO_INV:', r.id), r.reserved_qty
  FROM inventory_reservation r
 WHERE r.status = 1 AND r.reserved_qty > 0
   AND NOT EXISTS (SELECT 1 FROM inventory i
                    WHERE i.station_id = r.station_id AND i.product_id = r.product_id)
UNION ALL
SELECT CONCAT('NEED_MISMATCH:', r.id), r.reserved_qty
  FROM inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
 WHERE r.status = 1
   AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity);

-- ① 新出现的差异键（`NOT EXISTS` 而不是 `NOT IN`：后者遇到 NULL 会整体不成立）
SELECT COUNT(*) INTO @new_viol
  FROM `tmp_v64_viol_after` a
 WHERE NOT EXISTS (SELECT 1 FROM `tmp_v64_viol_before` b WHERE b.viol_key = a.viol_key);
-- ② 原有差异键**但数值变大**了（契约 A2：旧差异键不是"这次可以继续扩大错误"的白名单）
SELECT COUNT(*) INTO @worse_viol
  FROM `tmp_v64_viol_after` a
  JOIN `tmp_v64_viol_before` b ON b.viol_key = a.viol_key
 WHERE a.magnitude > b.magnitude;

SET @viol := @new_viol + @worse_viol
    -- ③ **在途(1,2)** 明细没有活跃凭据（2-3 已补齐 ⇒ 还有就是新问题）
    --    ⚠️ **不含已送达(3)**：状态 3 的货已出库，本来就不该有活跃凭据（契约 R1）
  + (SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id
      WHERE o.status IN (1, 2) AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
        WHERE r.order_item_id = oi.id AND r.status = 1))
    -- ④ 活跃凭据站别 ≠ 该单当前履约站（2-0 释放不该有的、2-1/2-2 搬在途单 ⇒ 还有就是新造的）
  + (SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
      WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id))
    -- ⑤ 预留量越界 / 需求快照与真相源不一致（2-0c 已挡在 DML 之前 ⇒ 这里兜一道）
  + (SELECT COUNT(*) FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id
      WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty
                              OR r.need_qty <> oi.quantity))
    -- ⑥ 在途(含已送达)单镜像与活跃凭据不一致（2-5 已统一重写 ⇒ 还有就是新造的）
    --    已送达(3) 也查：它的镜像必须是 0（没有活跃凭据）
  + (SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id
      LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
      WHERE o.status IN (1, 2, 3) AND COALESCE(oi.deducted_qty, 0) <> COALESCE(r.reserved_qty, 0))
    -- ⑦ **不该有活跃预留的单**（已送达 3 / 已完成 4 / 已取消 5）还挂着活跃凭据（2-0 已释放 ⇒ 兜一道）
  + (SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
      WHERE r.status = 1 AND o.status IN (3, 4, 5));
SELECT @new_viol AS newly_created_violations,
       @worse_viol AS worsened_preexisting_diffs,
       @viol AS precommit_violations_must_be_zero;

SET @commit_stmt := IF(@viol = 0, 'COMMIT',
                       'SELECT * FROM __V64_PRECOMMIT_INVARIANT_VIOLATED_TX_NOT_COMMITTED__');
PREPARE st_commit FROM @commit_stmt;
EXECUTE st_commit;
DEALLOCATE PREPARE st_commit;
-- ↑ 走到这里说明事务已 COMMIT（不变量全 0）。任意一步失败 ⇒ 整体回滚（执行方式见文件头）。

-- -----------------------------------------------------------------------------
-- 第 3 步：修复后自查（人工核对用）
--   期望：① ② ③ ④ 全为 0；⑤ 是"修不了、需要人工判断"的存量异常（见下方注释）
-- -----------------------------------------------------------------------------
-- ⚠️ 这里**必须分两条语句**：MySQL 不允许同一条语句里两次引用同一张**临时**表
--    （演练实测：把两个标量子查询写进一条 SELECT ⇒ `ERROR 1137 Can't reopen table: 'tmp_v64_topup'`，
--      而且那时第 2 步已经 COMMIT —— 数据落库了、脚本却报错、四项自查一条都打不出来）。
SELECT (SELECT COUNT(*) FROM `tmp_v64_move`)             AS moved_wrong_station,
       (SELECT COUNT(*) FROM `tmp_v64_missing`)          AS inserted_missing_credential,
       (SELECT COUNT(*) FROM `tmp_v64_terminal_cred`)    AS released_on_terminal_orders,
       (SELECT COUNT(*) FROM `tmp_v64_delivered_cred`)   AS released_on_delivered_orders;
SELECT COUNT(*) AS items_topped_up,
       COALESCE(SUM(add_qty), 0) AS qty_topped_up
  FROM `tmp_v64_topup` WHERE add_qty > 0;

-- ① 还有凭据挂错站？（期望 0）
SELECT COUNT(*) AS still_credential_wrong_station
  FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id);

-- ② 还有**在途(1,2)**明细没有活跃凭据？（期望 0）
--    ⚠️ **不含已送达(3)**：状态 3 = 货已出库，本来就没有活跃凭据（契约 R1）
SELECT COUNT(*) AS still_inflight_items_without_credential
  FROM order_item oi JOIN orders o ON o.id = oi.order_id
 WHERE o.status IN (1, 2)
   AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
                    WHERE r.order_item_id = oi.id AND r.status = 1);

-- ②b 还有**不该有活跃预留的单**（3/4/5）挂着活跃凭据？（期望 0）
SELECT COUNT(*) AS still_active_on_finished_orders
  FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND o.status IN (3, 4, 5);

-- ③ 预留量越界（负数或超过需求量快照）？（期望 0）
SELECT COUNT(*) AS still_reserved_out_of_range
  FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id
 WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty
                         OR r.need_qty <> oi.quantity);

-- ④ 在途/已送达单的镜像与活跃凭据不一致？（期望 0）
SELECT COUNT(*) AS still_mirror_mismatch
  FROM order_item oi JOIN orders o ON o.id = oi.order_id
  LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
 WHERE o.status IN (1, 2, 3) AND COALESCE(oi.deducted_qty, 0) <> COALESCE(r.reserved_qty, 0);

-- ⑤ 预留超实物（Σ活跃预留 > 在库实物）—— **本脚本不修**：
--    修它意味着从某些客户已经拿到的承诺里扣回来（那会让单突然完不成）。
--    有行说明该库实物本来就缺（历史漂移 / 盘亏没登记），交给对账 E11/SE7 报警后人工处置：
--    要么补货（入库后自动补位），要么按订单与客户协商取消。
--    ⚠️ 门禁只拦"本脚本新造的"（见 `preexisting_diffs_left_to_reconcile`）：存量差异不拦、但要人工跟进。
SELECT r.station_id, r.product_id, SUM(r.reserved_qty) AS reserved, MAX(i.quantity) AS physical
  FROM inventory_reservation r
  JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id
 WHERE r.status = 1
 GROUP BY r.station_id, r.product_id
HAVING SUM(r.reserved_qty) > MAX(i.quantity);

-- ⑥ 终态单（已完成 4 / 已取消 5）还挂着活跃凭据？（期望 0：2-0 已把它们释放并留痕）
SELECT r.order_id, o.status, COUNT(*) AS active_credentials
  FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND o.status IN (4, 5)
 GROUP BY r.order_id, o.status;

DROP TEMPORARY TABLE IF EXISTS `tmp_v64_viol_after`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_viol_before`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_terminal_cred`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_topup`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_missing`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v64_move`;
