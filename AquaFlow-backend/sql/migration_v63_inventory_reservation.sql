-- =============================================================================
-- v63 · 库存预留凭据：把"货为哪张单留在哪个站"变成可核对的事实
--
-- 依据：2026-09-25 架构评审报告 §5.4（问题 4，P0）+ 用户裁定「剩下的全修」；
--       设计稿正本：docs/design/28-库存预留与履约凭据.md（本脚本 = 该文档 §7 的落地）。
--
-- 要解决的两个场景（都已实测）：
--   ① 跨站外派后取消：A、B 各 10 桶，客户在 A 下 2 桶 → 外派给 B 履约 → 取消。
--      旧实现"下单扣 A、取消按**当时履约站** B 回补" ⇒ A=8、B=12（A 少的永不回来、B 凭空多 2）。
--   ② 缺货下单：库存 3、下单 10（客户确认缺货）→ 只记 deducted_qty=3，剩下 **7 桶永远不落账**
--      （补货后完成配送也不补扣）⇒ 实物与账目永久漂移，而库存对账等式**两边同时缺**、照样平。
--
-- 新模型（两个量、四个时点）：
--   下单       → 预留（占"可用量"，**不动** inventory.quantity）
--   入库/盘点增加 → 按 FIFO 把新货补给等货的单（只加 reserved_qty）
--   完成配送   → 出库（inventory.quantity −），锚定**当时履约站**；预留不足直接拒绝完成
--   换站       → 旧站凭据释放 + 新站按可用量重建（货跟着履约站走）
--   取消/拒单   → 释放预留（**不是**回补库存 —— 实物从没减过）
--
-- -----------------------------------------------------------------------------
-- 影响面
--   · 新增 1 张表 `inventory_reservation`（不改任何既有列的含义、不删数据）。
--     唯一键建在生成列 `active_order_item_id` 上：**仅当 status=1（预留中）取 order_item_id**，
--     于是"一条明细至多一份活跃凭据"，而历史行（已出库/已释放）可以留多条当审计轨迹
--     —— 换站就是"旧站那条置已释放 + 新站插一条新的"。
--     形状与既有的 `uk_payment_active_order` / `uk_earning_auto` 一致。
--   · **存量在途单的实物要"加回"**：老实现在**下单那一刻**就把 inventory.quantity 减掉了，
--     而新模型只在**完成配送**时减。若不还回去，那些单完成配送时会**二次扣减**。
--     所以本脚本对 `status IN (1,2)`（未送达、未取消）的单：把 `deducted_qty` 加回 `quantity`，
--     并补一条 INBOUND 流水（保持 V1-4 等式 `quantity == Σ inventory_record.delta` 成立），
--     同时按 `o.station_id`（**当初真正扣减的那个站**）建一条活跃凭据。
--     ⚠️ 已送达(3)/已完成(4)的单**不动**：它们的货确实已经出去了。
--   · 幂等：建表用 IF NOT EXISTS；回填用 `NOT EXISTS(该明细已有凭据)` 作为唯一判据
--     —— 重复执行不会再补一次（凭据是"这个明细处理过了"的标记，加回与建凭据在同一轮里成对完成）。
--
-- 执行顺序
--   · **代码先上**（新模型不认旧口径），再执行本脚本。执行前先 `mysqldump` 到 `backup/`。
--   · 脚本末尾会打印回填前后的对账自查（数量 + 流水双向），执行完请核对两行是否一致。
--
-- 回滚
--   · `DROP TABLE inventory_reservation;` 再把代码改回"下单扣、取消补"（OrderServiceImpl /
--     PaymentServiceImpl / OrderWorkflowServiceImpl 三处，见 28 号文档 §9）。
--   · ⚠️ 回滚前必须先把在途单的扣减**再减回去**（等价于反向执行第 2 步），否则库存会凭空多出来。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（关键表不在就中止，避免在别的库上瞎改）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db
                   AND TABLE_NAME IN ('inventory','inventory_record','orders','order_item'));
SELECT IF(@tbl_cnt = 4,
          'OK: 目标库校验通过（inventory / inventory_record / orders / order_item 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/4），已终止，未做任何修改')) AS precheck;

SET @abort := IF(@tbl_cnt <> 4, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：建表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inventory_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID（出库量按它算需求量）',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `station_id` bigint NOT NULL COMMENT '★ 这份凭据当前挂在哪个站：换站时改它，而不是订单的归属站（v63）',
  `reserved_qty` int NOT NULL DEFAULT '0' COMMENT '已预留在库量（≤ 订单量；差额=缺货待补，由入库按 FIFO 补齐）',
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
-- 第 2 步：存量在途单的实物"加回"（必须先加回、再建凭据；两件事同一轮完成）
--   判据：该明细还没有凭据（= 没处理过）+ 订单未送达未取消 + 当初确实扣过货（deducted_qty > 0）
--   站别：`o.station_id`（归属站）—— 老实现就是在**下单那一刻**从这一站扣的，
--   所以这正是"当初扣在哪一站"的真实答案（跨站外派不会搬库存，见问题 4a）。
-- -----------------------------------------------------------------------------
CREATE TEMPORARY TABLE `tmp_v63_backfill` AS
SELECT oi.id                AS order_item_id,
       oi.order_id          AS order_id,
       oi.product_id        AS product_id,
       o.station_id         AS station_id,
       oi.deducted_qty      AS qty
  FROM order_item oi
  JOIN orders o ON o.id = oi.order_id
 WHERE o.status IN (1, 2)
   AND oi.deducted_qty > 0
   AND NOT EXISTS (SELECT 1 FROM inventory_reservation r WHERE r.order_item_id = oi.id);

SELECT CONCAT('待回填明细数 = ', COUNT(*), '，合计数量 = ', COALESCE(SUM(qty), 0)) AS backfill_plan
  FROM `tmp_v63_backfill`;

-- 2a) 实物加回
UPDATE inventory i
  JOIN (SELECT station_id, product_id, SUM(qty) AS qty FROM `tmp_v63_backfill`
         GROUP BY station_id, product_id) t
    ON t.station_id = i.station_id AND t.product_id = i.product_id
   SET i.quantity = i.quantity + t.qty,
       i.update_time = NOW();

-- 2b) 补流水（V1-4 等式「quantity == Σ inventory_record.delta」的前提；不补就会立刻对账不平）
INSERT INTO inventory_record(station_id, product_id, delta, type, ref_id, operator_id, note, create_time)
SELECT station_id, product_id, SUM(qty), 'INBOUND', NULL, NULL,
       'v63 库存预留模型迁移：下单扣减改为预留，实物加回（该单完成配送时才出库）', NOW()
  FROM `tmp_v63_backfill`
 GROUP BY station_id, product_id;

-- 2c) 建活跃凭据（reserved_qty = 当初扣减量；预留量 ≤ 订单量，差额留给入库补）
INSERT INTO inventory_reservation(order_id, order_item_id, product_id, station_id,
                                  reserved_qty, shipped_qty, released_qty, status, create_time, update_time)
SELECT order_id, order_item_id, product_id, station_id, qty, 0, 0, 1, NOW(), NOW()
  FROM `tmp_v63_backfill`;

DROP TEMPORARY TABLE `tmp_v63_backfill`;

-- -----------------------------------------------------------------------------
-- 第 3 步：回读自查（人工核对用；不参与判定）
--   ① 表是否在；② 活跃凭据数与预留总量；③ 库存与流水的双向差额（都应为 0）
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inventory_reservation') AS table_ready,
       (SELECT COUNT(*) FROM inventory_reservation WHERE status = 1)          AS active_reservations,
       (SELECT COALESCE(SUM(reserved_qty), 0) FROM inventory_reservation WHERE status = 1) AS reserved_total;

-- 逐站逐商品：数量 − 流水合计（正=数量多于流水；负=流水多于数量）
SELECT i.station_id, i.product_id, i.quantity,
       COALESCE(r.sum_delta, 0) AS record_sum,
       i.quantity - COALESCE(r.sum_delta, 0) AS diff
  FROM inventory i
  LEFT JOIN (SELECT station_id, product_id, SUM(delta) AS sum_delta
               FROM inventory_record GROUP BY station_id, product_id) r
    ON r.station_id = i.station_id AND r.product_id = i.product_id
 WHERE i.quantity <> COALESCE(r.sum_delta, 0);
-- ↑ 期望**返回 0 行**。有行说明 `inventory_record` 与 `inventory` 已经对不上，
--   请先查清原因（很可能该库之前就有历史漂移），不要带着差异继续。
