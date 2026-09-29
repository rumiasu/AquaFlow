-- =============================================================================
-- v63 · 库存预留凭据：把"货为哪张单留在哪个站"变成可核对的事实
--
-- 依据：2026-09-25 架构评审报告 §5.4（问题 4，P0）+ 用户裁定「剩下的全修」；
--       设计稿正本：docs/design/28-库存预留与履约凭据.md（本脚本 = 该文档 §7 的落地，
--       §11.9 是 2026-09-25 二次收口修订，效力高于前文）。
--
-- ⚠️ 本脚本已被二次收口（二次验收意见 B3）改过一轮，与首版/上一版的差别：
--   1) **分配公式按"真实可用量"**：实物 − **既有活跃预留** − 本批已分配量。
--      上一版的公式只减了"本批待补明细的前缀和"，漏掉了**不在本批计划里、但已经活跃的预留**
--      ⇒ 混合状态（代码先上、新订单已建凭据，然后才跑迁移）下会算出 Σ预留 > 实物。
--   2) **凭据一律先以 reserved_qty = 0 插入，再由"全局补位"统一分配**（与 v64 同一算法）：
--      分配的目标集合是"该 (站,商品) 的**全部**活跃凭据"（含既有的与本次新建的），
--      按 `need_time`（下单时间快照）FIFO —— 既有等待单与本次恢复量一起参与，谁先下单谁先拿。
--   3) **提交前不变量门禁**：关键不变量在**同一个事务里**检查，不满足就**不 COMMIT**（报错回滚），
--      不再"先 COMMIT 再 SELECT 出差异还退出 0"。文末的 SELECT 只是给人看的补充证据。
--   4) **原扣减站没有库存行 ⇒ 预检中止**（不生成依赖不存在实物的承诺）：
--      `restore_qty > 0` 但 (restore_station, product) 没有 inventory 行时，DML 之前就报错退出。
--   5) **需要 need_qty / need_time 列**（v65 加的）：表已存在但没有这两列 ⇒ 先跑 v65。
--
-- 要解决的两个场景（都已实测）：
--   ① 跨站外派后取消：A、B 各 10 桶，客户在 A 下 2 桶 → 外派给 B 履约 → 取消。
--      旧实现"下单扣 A、取消按**当时履约站** B 回补" ⇒ A=8、B=12（A 少的永不回来、B 凭空多 2）。
--   ② 缺货下单：库存 3、下单 10（客户确认缺货）→ 只记 deducted_qty=3，剩下 **7 桶永远不落账**。
--   ⚠️ 事实更正（二次验收意见 §4.3）：**不能**说"老实现扣库存不写流水，所以真实老库必然对账不平"。
--      本仓审查起点之前的代码在扣减时**同时**写了 CONSUME 流水（`d703515:OrderServiceImpl`
--      的 `decreaseStock` + `recordChange(..., CONSUME, ...)`），正常老库两边是对得上的；
--      但历史库可能被手工改过、跑过别的脚本 —— **执行前必须实测基线**（见文末自查④）。
--
-- 新模型（两个量、四个时点）：
--   下单       → 预留（占"可用量"，**不动** inventory.quantity）
--   入库/盘点增加 → 按 FIFO 把新货补给等货的单（只加 reserved_qty）
--   完成配送   → 出库（inventory.quantity −），锚定**当时履约站**；预留不足直接拒绝完成
--   换站       → 旧站凭据释放 + 新站按可用量重建（货跟着履约站走）
--   取消/拒单   → 释放预留（**不是**回补库存 —— 实物从没减过）
--
-- -----------------------------------------------------------------------------
-- 执行方式（**硬要求**，二次收口契约 §2 B3 最后一条）
--   · 用**出错即停止**的客户端执行（mysql CLI 的默认行为）：`mysql ... < 本脚本`
--     —— **绝对不要**加 `--force`（那会跳过错误继续执行）。
--   · 为什么强调：SQL 语句失败**不自动**等价于整个事务回滚。本脚本靠两件事保证安全：
--     ① 出错时客户端停下、连接断开 ⇒ 未提交事务被 InnoDB 回滚；
--     ② 不变量门禁把 `COMMIT` **本身**做成条件语句：不满足时执行的不是 COMMIT 而是必然失败的语句，
--        于是**即使**有人用了 `--force`，也不会把不合规的数据提交上去（事务在会话结束时回滚）。
--   · ⚠️ **退出码只有在"不加 `--force`"时才可信**：演练实测 `--force` 下即使撞到门禁/报错，
--     客户端仍会一路跑完并返回 **0**（数据确实没提交，但退出码会骗人）。操作卡上写"退出码 0 才算成功"
--     必须同时写明这条前提。
--   · ⚠️ marker 表名在报错信息里的**大小写随服务器 `lower_case_table_names` 变**（本机为 1 ⇒ 显示小写；
--     Linux 上为大写）。写自动化断言时按大小写不敏感匹配。
--   · 执行前必须：**停止业务写入** + `mysqldump` 到 `backup/`。
--
-- ⚠️ **不要手工 DELETE `inventory_reservation` 的行来"退掉"一次迁移**：
--   判据是"该明细有没有凭据 / 该单有没有 v63 加回流水"，删行会让它重新满足条件 ⇒ 实物被**再加一次**
--   （演练实测 A 站 10 → 14）。要作废一条凭据，请像运行时那样置 `status=3` + `released_qty=reserved_qty` 留痕。
--
-- 幂等 / 升级路径
--   · 幂等：待回填明细的判据是"该明细**没有活跃凭据**"，而"要不要加回实物"的判据是
--     "该明细**有没有任何凭据**（或该单有没有 v63 加回流水）"（加回过就不再加）。重复执行：计划为空、补位无闲货 ⇒ 零变化。
--   · 表已存在但缺 need_qty/need_time（跑过上一版 v63 的库）：先执行 `migration_v65_reservation_need_snapshot.sql`。
--   · **跑过旧版 v63 的库（凭据挂在归属站）：本脚本会主动中止并指向 v64**（第 2-4 步的路由预检）——
--     不要再"先跑 v63 再跑 v64"，那条路会先在门禁上撞墙。**决定路径前先跑只读探针**
--     `sql/probe_inventory_reservation_state.sql`，按它的 ROUTE 提示走。
--   · 判据不是"表在不在、有没有两列"，而是**数据转换状态与转换证据**（有没有错站凭据 / 有没有待恢复实物的明细）。
--
-- 回滚
--   · `DROP TABLE inventory_reservation;` 再把代码改回"下单扣、取消补"（见 28 号文档 §9）。
--   · ⚠️ 回滚前必须先把在途单的扣减**再减回去**（等价于反向执行第 2 步），否则库存会凭空多出来。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检（关键表 + 表结构版本；不通过就中止，未做任何修改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('inventory','inventory_record','orders','order_item'));
SELECT IF(@tbl_cnt = 4,
          'OK: 目标库校验通过（inventory / inventory_record / orders / order_item 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/4），已终止，未做任何修改')) AS precheck;

SET @res_exists := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation');
SET @res_cols := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation'
                    AND COLUMN_NAME IN ('need_qty','need_time'));
SELECT IF(@res_exists = 0 OR @res_cols = 2,
          IF(@res_exists = 0, 'OK: 首次转换（凭据表还不存在，本脚本负责建表）',
             'OK: 凭据表已存在且带 need_qty/need_time（可安全重跑补位）'),
          CONCAT('ABORT: 凭据表存在但缺 need_qty/need_time（命中 ', @res_cols, '/2）',
                 ' —— 请先执行 migration_v65_reservation_need_snapshot.sql')) AS schema_check;

SET @abort := IF(@tbl_cnt <> 4 OR (@res_exists = 1 AND @res_cols <> 2),
                 'SELECT * FROM __ABORT_V63_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：建表（含需求快照列；老库由 v65 补这两列）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inventory_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID（出库量按它算需求量）',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `station_id` bigint NOT NULL COMMENT '★ 这份凭据当前挂在哪个站：换站时改它，而不是订单的归属站（v63）',
  `need_qty` int NOT NULL COMMENT '★ 需求量快照（= 下单那一刻 order_item.quantity，v65）：补位排序/分配只读它，不再 join order_item —— 见 docs/design/28 §11.9（旧快照下 join 读不到刚提交的新明细）',
  `need_time` datetime NOT NULL COMMENT '★ 业务需求时间快照（= 下单那一刻 orders.create_time，v65）：FIFO 排序依据；换站重建时从旧凭据复制（凭据 id 会变、需求时间不变）',
  `reserved_qty` int NOT NULL DEFAULT '0' COMMENT '已预留在库量（≤ need_qty；差额=缺货待补，由入库按 FIFO 补齐）',
  `shipped_qty` int NOT NULL DEFAULT '0' COMMENT '已出库量（完成配送时一次性写满）',
  `released_qty` int NOT NULL DEFAULT '0' COMMENT '已释放量（取消/换站时旧凭据作废的量）',
  `status` tinyint NOT NULL COMMENT '1=预留中 2=已出库 3=已释放（只前进，见 constant/ReservationStatus）',
  `active_order_item_id` bigint GENERATED ALWAYS AS ((case when (`status` = 1) then `order_item_id` else NULL end)) STORED COMMENT '生成列：仅"预留中"取 order_item_id，用于唯一键「一条明细至多一份活跃凭据」',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_active_item` (`active_order_item_id`),
  KEY `idx_reservation_station_product_status` (`station_id`,`product_id`,`status`),
  KEY `idx_reservation_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存预留凭据：货为哪张单留在哪个站（v63，见 docs/design/28）';

-- -----------------------------------------------------------------------------
-- 第 2 步：备好计划（三张**临时表**，只读不改数据）
--   ⚠️ MySQL 不允许多次引用同一张临时表，所以"明细 / 加回汇总 / 缺库存行明细"各存一张。
-- -----------------------------------------------------------------------------
-- 2-1) 待建凭据的明细：在途单(1,2) 且**没有活跃凭据**（含 deducted_qty = 0 的全缺货明细）
--      · `restore_qty`：只有"这条明细**一条凭据都没有**"时才等于 deducted_qty（= 老实现下单时扣掉的实物，
--        要还给原扣减站）；已经有过凭据（例如已释放的历史行）的明细记 0 —— 实物上一轮已经加回过了，
--        再加一次就是凭空造库存。但**凭据仍然要补一条活跃的**，否则它会卡在"完成配送时凭据不完整"。
--      · ⚠️ 再加一道**流水护栏**（2026-09-25 演练发现）：判据里还要求"该单没有 v63 的加回流水" ——
--        因为有人**手工 DELETE 掉凭据行**之后，上一条判据会退化成"从没建过凭据"，重跑就会**再加一次实物**
--        （演练实测：A 站 10 → 14）。加回流水带 `ref_id = 订单 id`，它才是"这单加回过"的持久证据。
--      · `need_qty` / `need_time`：从真相源（order_item.quantity / orders.create_time）取一次，
--        随凭据一起落库（v65 的列，之后补位只读凭据行）。
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_items`;
CREATE TEMPORARY TABLE `tmp_v63_items` AS
SELECT oi.id                                   AS order_item_id,
       oi.order_id                             AS order_id,
       oi.product_id                           AS product_id,
       oi.quantity                             AS need_qty,
       o.create_time                           AS need_time,
       CASE WHEN EXISTS (SELECT 1 FROM inventory_reservation r WHERE r.order_item_id = oi.id)
              OR EXISTS (SELECT 1 FROM inventory_record ir
                          WHERE ir.ref_id = oi.order_id AND ir.note LIKE 'v63 库存预留模型迁移%')
            THEN 0
            ELSE COALESCE(oi.deducted_qty, 0) END AS restore_qty,
       o.station_id                            AS restore_station,
       COALESCE(o.delivery_station_id, o.station_id) AS cred_station
  FROM order_item oi
  JOIN orders o ON o.id = oi.order_id
 WHERE o.status IN (1, 2)
   AND NOT EXISTS (SELECT 1 FROM inventory_reservation r2
                    WHERE r2.order_item_id = oi.id AND r2.status = 1);

-- 2-2) 逐 (原扣减站, 商品) 的加回汇总 —— 实物加回与补流水都用它
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_restore`;
CREATE TEMPORARY TABLE `tmp_v63_restore` AS
SELECT restore_station AS station_id, product_id, SUM(restore_qty) AS qty
  FROM `tmp_v63_items`
 WHERE restore_qty > 0
 GROUP BY restore_station, product_id;

-- 2-3) **预检**：要加回实物、但该 (原扣减站, 商品) 根本没有 inventory 行的明细
--      ⇒ 不能凭空建正数预留（二次验收 B3 最后一段）。这批明细必须人工先处理。
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_missing_inv`;
CREATE TEMPORARY TABLE `tmp_v63_missing_inv` AS
SELECT t.order_item_id, t.order_id, t.restore_station, t.product_id, t.restore_qty
  FROM (SELECT i.* FROM `tmp_v63_items` i WHERE i.restore_qty > 0) t
  LEFT JOIN inventory inv ON inv.station_id = t.restore_station AND inv.product_id = t.product_id
 WHERE inv.id IS NULL;

SELECT CONCAT('待建凭据明细数 = ', COUNT(*), '（其中当初有扣减的 ', COALESCE(SUM(restore_qty > 0), 0),
              ' 条 / 合计加回 ', COALESCE(SUM(restore_qty), 0), ' 桶）') AS backfill_plan
  FROM `tmp_v63_items`;
-- ↑ ⚠️ `COALESCE(SUM(...), 0)` 不能省：计划为空时 `SUM(...)>0` 是 NULL，而 CONCAT 会把 NULL 传播出去，
--    于是这一行打印成 NULL 而不是"待建凭据明细数 = 0"（演练发现：拿它做幂等断言会永远不命中）。
SELECT COUNT(*) AS missing_inventory_rows FROM `tmp_v63_missing_inv`;

-- 2-4) **迁移路由预检**（二次收口验收 M2）：这个库到底该跑哪个脚本？
--      · `wrong_station_active > 0` ⇒ 本库跑过**旧版 v63**（它把凭据建在归属站）。
--        本脚本的提交门禁第④条会拒绝这种数据（正确行为），但那时已经白跑一遍 ——
--        所以**在 DML 之前**就停下，并明确指向 v64（v64 就是为修这批数据写的）。
--      · `active_on_finished_order > 0` ⇒ **已不该有活跃预留的单**（已送达 3 / 已完成 4 / 已取消 5）
--        上还挂着活跃凭据（契约 R1）：3 是"货已出库、只等收钱"（`completeDelivery` 里 `shipForOrder`
--        已把凭据置为已出库、实物已减），4/5 是终态 —— 这两种都不该有活跃预留。
--        本脚本**不碰**它们（它只处理在途 1/2），所以要停下并指向 v64（v64 会按出库证据释放）。
--      · 若同时还有"待恢复实物的明细"（一条凭据都没有、但 deducted_qty > 0），
--        说明有人**手工删过凭据行**之类的异常：两条路都不安全（v63 会被门禁拒、v64 不会恢复实物）
--        ⇒ 明确拒绝并列出待核明细，**不猜**（契约 §3 状态 5 的原话）。
SET @wrong_station_active := (SELECT COUNT(*) FROM inventory_reservation r
                               JOIN orders o ON o.id = r.order_id
                              WHERE r.status = 1
                                AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id));
SET @active_on_finished := (SELECT COUNT(*) FROM inventory_reservation r
                             JOIN orders o ON o.id = r.order_id
                            WHERE r.status = 1 AND o.status IN (3, 4, 5));
SET @restore_pending := (SELECT COUNT(*) FROM `tmp_v63_items` WHERE restore_qty > 0);
SELECT @wrong_station_active AS wrong_station_active,
       @active_on_finished   AS active_on_finished_order,
       @restore_pending      AS restore_pending;

SELECT IF(@wrong_station_active + @active_on_finished = 0,
          'ROUTE: 未发现错站凭据、也没有"不该有活跃预留的单" ⇒ 本库不需要 v64 的修复，按探针 ROUTE 走 v63（本脚本）→ v64（通常空操作）',
          IF(@restore_pending = 0,
             CONCAT('ROUTE: 发现 ', @wrong_station_active, ' 条错站凭据 / ', @active_on_finished,
                    ' 条挂在已送达或终态单上的活跃凭据 ⇒ 本库属于"旧版 v63 已执行"或"R1 形状"：请**改跑 v64**（它搬错站/补漏/按出库证据释放不该有的活跃预留），本脚本已中止且未改任何数据'),
             'ROUTE: 既有错站凭据或不该有的活跃预留、又有待恢复实物的明细 ⇒ 两种脚本都不安全（可能被手工删过凭据行）：请先人工核对，本脚本已中止且未改任何数据'))
       AS routing_hint;

-- 错站凭据存在 ⇒ 在 DML 之前中止（把路由交给 v64）
SET @abort_route := IF(@wrong_station_active > 0,
                       'SELECT * FROM __ABORT_V63_USE_V64_FOR_WRONG_STATION_CREDENTIALS__', 'SELECT 1');
PREPARE st_route FROM @abort_route;
EXECUTE st_route;
DEALLOCATE PREPARE st_route;

-- 已送达/终态单上仍有活跃预留 ⇒ 同样在 DML 之前中止（交给 v64 按出库证据释放）
SET @abort_finished := IF(@active_on_finished > 0,
                          'SELECT * FROM __ABORT_V63_USE_V64_FOR_ACTIVE_ON_FINISHED_ORDER__', 'SELECT 1');
PREPARE st_abort_fin FROM @abort_finished;
EXECUTE st_abort_fin;
DEALLOCATE PREPARE st_abort_fin;

-- 预检不通过 ⇒ 在 DML 之前中止（数据零变化）
SET @abort2 := IF((SELECT COUNT(*) FROM `tmp_v63_missing_inv`) > 0,
                  'SELECT * FROM __ABORT_V63_RESTORE_STATION_HAS_NO_INVENTORY_ROW__', 'SELECT 1');
PREPARE st_abort2 FROM @abort2;
EXECUTE st_abort2;
DEALLOCATE PREPARE st_abort2;

-- 2-4b) **脏需求快照预检**（契约 A2 / 验收 R2）：需求快照是本次分配的依据
--       （3-4 的缺口 = `need_qty − reserved_qty`），快照与真相源不一致或预留越界时**绝不能**
--       拿它继续分配 —— 那会把错误放大，而且门禁看不出来。
--       演练实测（`docs/audit/history/drill/drill_r2_dirty_need_snapshot.sql`）：实物 10、真实需求 2、
--       预留 2、镜像 2、坏快照 `need_qty = 10` ⇒ 上一版 v63 按坏快照给它再补 8，
--       预留与镜像都变 10、可用量 8 → **0**；差异键还是原来那一个 ⇒ `newly_created_violations = 0`
--       ⇒ **照样提交**。一个只要 2 桶的订单占满了整站可用量。
--       ⇒ 定为**提交前拒绝**：列出明细、DML 之前中止。人工处置通常是
--         `UPDATE inventory_reservation SET need_qty = (该明细的 order_item.quantity) WHERE id = …`
--         （真相源是 `order_item.quantity` / `orders.create_time`，见 docs/design/28 §11.9）。
--       ⚠️ 这与"历史库存账实差额"是**两类东西**：账实差额（Σ预留 > 实物、凭据挂在没库存行的站）
--         不参与本次分配，可以留给对账；坏快照**直接决定这次要分多少货**，不能放行。
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_dirty_need`;
CREATE TEMPORARY TABLE `tmp_v63_dirty_need` AS
SELECT r.id AS reservation_id, r.order_id, r.order_item_id, r.station_id, r.product_id,
       r.need_qty, oi.quantity AS item_quantity, r.reserved_qty
  FROM inventory_reservation r
  JOIN order_item oi ON oi.id = r.order_item_id
 WHERE r.status = 1
   AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty OR r.need_qty <> oi.quantity);

SELECT COUNT(*) AS dirty_need_snapshot FROM `tmp_v63_dirty_need`;

-- 明细清单（人工核对用；≤50 行，别让操作人自己写 SQL 找）
SELECT reservation_id, order_id, order_item_id, station_id, product_id,
       need_qty, item_quantity, reserved_qty
  FROM `tmp_v63_dirty_need` ORDER BY reservation_id LIMIT 50;

SET @abort_dirty := IF((SELECT COUNT(*) FROM `tmp_v63_dirty_need`) > 0,
                       'SELECT * FROM __ABORT_V63_DIRTY_NEED_SNAPSHOT__', 'SELECT 1');
PREPARE st_abort_dirty FROM @abort_dirty;
EXECUTE st_abort_dirty;
DEALLOCATE PREPARE st_abort_dirty;

-- 2-5) **迁移前基线**（二次验收后的文档一致性核查 P0-2）
--      脚本头部承诺"实物不够的库不会被它修平，差额留给对账 E11" —— 但上一版门禁①对"Σ预留 > 实物"
--      一律拒绝 COMMIT，于是**存量差异会把整笔迁移拦死**（承诺与行为相反）。
--      修法：把"迁移**之前**就存在的三类存量差异"先记下来，门禁只拦**本脚本新造出来的**差异；
--      存量差异照样打出来，交给对账（E11/E15/E16）与人工处置。
--      ⚠️ 这三类都是"账实不符"的存量问题，**不是**本脚本的职责（本脚本只做等量搬运与建凭据）。
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_viol_before`;
CREATE TEMPORARY TABLE `tmp_v63_viol_before` AS
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

SELECT COUNT(*) AS preexisting_diffs_left_to_reconcile FROM `tmp_v63_viol_before`;
-- ↑ >0 不代表脚本会失败：这些是**迁移前就有的**账实差异，跑完仍然在，由对账 E11/E15/E16 报给运维。
--   但"跑完还在"只允许**不变大** —— 门禁会比较每个键的 `magnitude`（契约 A2/R2）。

-- -----------------------------------------------------------------------------
-- 第 3 步：一个事务里完成（凭据先写 = "该明细已处理"的标记；不变量校验通过才 COMMIT）
-- -----------------------------------------------------------------------------
START TRANSACTION;

-- 3-1) 建活跃凭据：**一律先记 0**，由 3-4 的全局补位按真实可用量分配
INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id,
                                  need_qty, need_time, reserved_qty, shipped_qty, released_qty,
                                  status, create_time, update_time)
SELECT order_id, order_item_id, product_id, cred_station,
       need_qty, need_time, 0, 0, 0, 1, NOW(), NOW()
  FROM `tmp_v63_items`;

-- 3-2) 实物加回**原扣减站**（只有真正存在的 inventory 行会被加；缺行的情况已在预检中止）
UPDATE inventory i
  JOIN `tmp_v63_restore` t
    ON t.station_id = i.station_id AND t.product_id = i.product_id
   SET i.quantity = i.quantity + t.qty,
       i.update_time = NOW();

-- 3-3) 补流水（V1-4 等式「quantity == Σ inventory_record.delta」的前提；与 3-2 同一批行）
--      ⚠️ **按订单逐条写**（`ref_id = order_id`、note 以 'v63 库存预留模型迁移' 开头），不要按 (站,商品) 汇总成一条：
--        · 惯例如 [AQ-029]：库存流水的 ref_id 指向单据，汇总一条会让"这笔加回是哪张单的"永久丢失；
--        · 更重要：它是 2-1 里那道**防二次加回**的持久证据（有人手工删掉凭据行之后，
--          重跑仍然能从这条流水看出"这单已经加回过"）。演练实测过没有它的后果：A 站 10 → 14。
INSERT INTO inventory_record(station_id, product_id, delta, type, ref_id, operator_id, note, create_time)
SELECT t.restore_station, t.product_id, t.restore_qty, 'INBOUND', t.order_id, NULL,
       'v63 库存预留模型迁移：下单扣减改为预留，实物加回原扣减站（该单完成配送时才出库）', NOW()
  FROM `tmp_v63_items` t
  JOIN inventory i ON i.station_id = t.restore_station AND i.product_id = t.product_id
 WHERE t.restore_qty > 0;

-- 3-4) **全局补位**：把每个 (站,商品) 的真实闲货按 need_time（下单时间）FIFO 分给有缺口的活跃凭据
--      可用量 = 实物（3-2 之后） − **该对全部活跃预留之和**（含既有凭据与本次新建的）
--      前缀和用"缺口"：一旦某条被 clamp 到闲货上限，闲货就分完了，后面的必然拿 0 ⇒ 与逐个贪心等价。
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_topup`;
CREATE TEMPORARY TABLE `tmp_v63_topup` AS
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
  JOIN `tmp_v63_topup` t ON t.id = r.id
   SET r.reserved_qty = r.reserved_qty + t.add_qty,
       r.update_time = NOW()
 WHERE r.status = 1 AND t.add_qty > 0;

-- 3-5) 同步 `order_item.deducted_qty` 镜像（唯一语义 = 活跃凭据的 reserved_qty）
--      ⚠️ **覆盖全部在途单，不能只同步本批新建的凭据**（二次收口验收 M1）：
--      3-4 的全局补位会**改到既有凭据**（例如"较早那张单自己也有缺口"时，它会被补上），
--      如果这里只同步 `tmp_v63_items`，那些既有凭据的镜像就会留在旧值 ⇒ 3-6 门禁的第六项立刻报差异
--      ⇒ 合法迁移整笔回滚（门禁没错，是这里的写集合比实际改动小）。
--      ⚠️ **含"已送达(3)"**（契约 R1）：已送达的明细**不该有活跃凭据**（货已出库，凭据是"已出库"状态），
--      所以它的镜像必须是 0；老库里可能留着旧值，这里一并归零。已完成(4)/已取消(5) 的历史行**刻意不碰**。
UPDATE order_item oi
  JOIN orders o ON o.id = oi.order_id
  LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
   SET oi.deducted_qty = COALESCE(r.reserved_qty, 0)
 WHERE o.status IN (1, 2, 3);

-- 3-6) **提交前不变量门禁**：任何一条不满足 ⇒ 本事务不 COMMIT（见文件头的执行方式说明）
--      ⚠️ 判据是"**本脚本新造出来的**差异"（见 2-5 的基线）：迁移前就存在的账实差异属于存量问题，
--      上一版把存量差异也拦下来 ⇒ 与脚本头"差额留给对账"的承诺相反，且会把合法迁移拦死
--      （2026-09-25 文档一致性核查 P0-2）。存量差异照样打印出来，交对账 E11/E15/E16。
--      （先把"现在的差异清单"落成临时表：MySQL 不允许同一条语句里两次引用临时表。）
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_viol_after`;
CREATE TEMPORARY TABLE `tmp_v63_viol_after` AS
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
  FROM `tmp_v63_viol_after` a
 WHERE NOT EXISTS (SELECT 1 FROM `tmp_v63_viol_before` b WHERE b.viol_key = a.viol_key);
-- ② 原有差异键**但数值变大**了（契约 A2：旧差异键不是"这次可以继续扩大错误"的白名单）
SELECT COUNT(*) INTO @worse_viol
  FROM `tmp_v63_viol_after` a
  JOIN `tmp_v63_viol_before` b ON b.viol_key = a.viol_key
 WHERE a.magnitude > b.magnitude;

SET @viol := @new_viol + @worse_viol
    -- ③ 在途明细没有活跃凭据（本脚本必须补齐；补完还有 ⇒ 是新问题）
  + (SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id
      WHERE o.status IN (1, 2) AND NOT EXISTS (SELECT 1 FROM inventory_reservation r
        WHERE r.order_item_id = oi.id AND r.status = 1))
    -- ④ 活跃凭据站别 ≠ 该单当前履约站（错站凭据已在 2-4 路由预检里挡掉 ⇒ 还有就是新造的）
  + (SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
      WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id))
    -- ⑥ 在途单镜像与活跃凭据不一致（3-5 已统一重写 ⇒ 还有就是新造的）
  + (SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id
      LEFT JOIN inventory_reservation r ON r.order_item_id = oi.id AND r.status = 1
      WHERE o.status IN (1, 2) AND COALESCE(oi.deducted_qty, 0) <> COALESCE(r.reserved_qty, 0))
    -- ⑦ 已送达/终态单上还有活跃预留（2-4 已挡在 DML 之前 ⇒ 这里再兜一道）
  + (SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
      WHERE r.status = 1 AND o.status IN (3, 4, 5));
SELECT @new_viol AS newly_created_violations,
       @worse_viol AS worsened_preexisting_diffs,
       @viol AS precommit_violations_must_be_zero;

SET @commit_stmt := IF(@viol = 0, 'COMMIT',
                       'SELECT * FROM __V63_PRECOMMIT_INVARIANT_VIOLATED_TX_NOT_COMMITTED__');
PREPARE st_commit FROM @commit_stmt;
EXECUTE st_commit;
DEALLOCATE PREPARE st_commit;
-- ↑ 走到这里说明事务已 COMMIT（不变量全 0）。任意一步失败 / 客户端断开 ⇒ InnoDB 回滚，重跑与成功一次相同。

-- -----------------------------------------------------------------------------
-- 第 4 步：事务后自查（人工核对用，**不是**判据 —— 判据是上面那道门禁）
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM `tmp_v63_items`)    AS planned_items,
       (SELECT COUNT(*) FROM inventory_reservation WHERE status = 1) AS active_reservations,
       (SELECT COALESCE(SUM(reserved_qty), 0) FROM inventory_reservation WHERE status = 1) AS reserved_total;

-- ⚠️ 补位结果**单独一条语句**：MySQL 不允许同一条语句里两次引用同一张临时表
-- （上一版就在这里踩了 `ERROR 1137 Can't reopen table`：自查语句报错、数据却已提交）。
SELECT COUNT(*) AS items_topped_up,
       COALESCE(SUM(add_qty), 0) AS qty_topped_up
  FROM `tmp_v63_topup` WHERE add_qty > 0;

-- ① 还有在途明细没有活跃凭据？（期望 0）
SELECT COUNT(*) AS inflight_items_without_credential
  FROM `tmp_v63_items` t
  LEFT JOIN inventory_reservation r ON r.order_item_id = t.order_item_id AND r.status = 1
 WHERE r.id IS NULL;

-- ② 凭据站别 ≠ 该单当前履约站？（期望 0）
SELECT COUNT(*) AS credential_wrong_station
  FROM inventory_reservation r JOIN orders o ON o.id = r.order_id
 WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id);

-- ③ 预留超实物？—— ⚠️ 这里看的是**全量现状**（含 2-5 基线里那些存量差异）；
--    门禁只拦"新造的"，所以本项 >0 不一定是本次迁移的错。要判断归因，请对比
--    跑之前的 `preexisting_diffs_left_to_reconcile` 与跑之后的这一项。
SELECT r.station_id, r.product_id, SUM(r.reserved_qty) AS reserved, MAX(i.quantity) AS physical
  FROM inventory_reservation r
  JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id
 WHERE r.status = 1
 GROUP BY r.station_id, r.product_id
HAVING SUM(r.reserved_qty) > MAX(i.quantity);

-- ④ 库存与流水双向差额（**先留基线再比**：本脚本只做等量搬运，不会修历史漂移 —— 期望"差额不变"）
SELECT i.station_id, i.product_id, i.quantity,
       COALESCE(r.sum_delta, 0) AS record_sum,
       i.quantity - COALESCE(r.sum_delta, 0) AS diff
  FROM inventory i
  LEFT JOIN (SELECT station_id, product_id, SUM(delta) AS sum_delta
               FROM inventory_record GROUP BY station_id, product_id) r
    ON r.station_id = i.station_id AND r.product_id = i.product_id
 WHERE i.quantity <> COALESCE(r.sum_delta, 0);

-- ⑤ 明细镜像与活跃凭据不一致？（期望 0）
SELECT COUNT(*) AS deducted_qty_mirror_mismatch
  FROM `tmp_v63_items` t
  JOIN order_item oi ON oi.id = t.order_item_id
  LEFT JOIN inventory_reservation r ON r.order_item_id = t.order_item_id AND r.status = 1
 WHERE COALESCE(oi.deducted_qty, 0) <> COALESCE(r.reserved_qty, 0);

-- ⑥ 活跃凭据挂在没有库存行的 (站,商品) 上、**且承诺了数量**？（期望 0；对账 E16 也查这条）
--    ⚠️ 条件是 `reserved_qty > 0`：外派到"没配这个商品"的站是**合法动作**（那边可用量 0 ⇒ 凭据 reserved=0
--    = 缺货待补），只按"有没有库存行"判会把合法状态算成差异。
SELECT COUNT(*) AS credential_without_inventory_row
  FROM inventory_reservation r
 WHERE r.status = 1 AND r.reserved_qty > 0
   AND NOT EXISTS (SELECT 1 FROM inventory i
                    WHERE i.station_id = r.station_id AND i.product_id = r.product_id);

DROP TEMPORARY TABLE IF EXISTS `tmp_v63_viol_after`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_viol_before`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_topup`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_missing_inv`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_restore`;
DROP TEMPORARY TABLE IF EXISTS `tmp_v63_items`;
