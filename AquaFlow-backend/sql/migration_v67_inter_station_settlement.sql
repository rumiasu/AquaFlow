-- =============================================================================
-- v67 · 站间结算台账（跨站单的「谁欠谁、欠多少、什么时候算办完」）
--
-- 背景（实测，正本 `docs/design/31-站间结算算例-水票计价-决策件.md` §6.1/§6.2）：
--   跨站外派单（归属 A / 履约 B）的三个东西**落在两个站**，系统里**没有任何一处把它们对上**：
--     · 钱：`payment_record.station_id` = **归属站**（票钱/微信款都收在 A）—— 实测站2 名下 0 条流水；
--     · 营收：`coalesce(settle_station_id, delivery_station_id, station_id)` = **接单站 B**；
--     · 票批次：`ticket_lot.station_id` = 归属站 A（票是预付，买票时就付过了）。
--   ⇒ A 拿着钱、B 出车出人出库存却账上没有钱。实测全库跨站单：**13 单 / ¥168.00**，
--     两站净额 +168 / −168 **相加为 0（闭合）**——这是一条可作断言的不变式。
--
-- 本表只做一件事：把"谁该付给谁多少、什么时候算办完"**记下来**。
--   它是 `docs/design/31` §6.2 那两条既有字段相减算法的**落点**，不是第二套账：
--   金额一律由代码算出后写快照，本表**不自算任何金额、不参与客户侧对账**。
--
-- ★ 计价依据（`basis`）—— 2026-09-27 产品已拍板（`docs/design/31` §8）：
--     1 = 本单营收照实结（**非票单**：微信/已收现金单，金额 = 水费 + 配送费 + 楼层费）
--     2 = 水票**折算实付**（默认，§8.1：逐张 `ticket_record.unit_price` 求和 ——
--         一张单可能跨批次消耗，逐张求和天然等于"本单实际消耗的那几张的实付价"，
--         且与退款回补同源）
--     3 = 水票**按挂牌价**（§8.2：**卖票站站长**可选，差价由卖票站自己承担）
--   `unit_price` / `ticket_qty` 是**快照**：此后新批次拉低均价、批次被回补、
--   都不改本行已算出的数。
--
-- ⚠️ `fee_amount`（票覆盖的配送费 / 楼层费）**v67 不计入 `amount`**：
--   「这两笔要不要一起结给履约站」是 `docs/design/31` §8.4 第 3 问，**产品尚未回答**。
--   单独记一列是为了拍板后**一个 UPDATE 就能启用**（`amount = amount + fee_amount`），
--   不必回头补历史数据。**不要**在没有拍板前把它加进 amount。
--
-- ⚠️ `status = 3 已冲销` 的用途：票单取消时 `restoreTicketsForOrder` 会回补批次，
--   若那时已经结过站间款，必须能冲销（`docs/design/31` §4 第 3 条）。
--   冲销**不删除本行**（一单一笔，uk 建在 `order_id` 上）—— 改成状态，
--   轨迹才可查；重建方向也走同一行的 UPDATE。
--
-- ⚠️ 不加外键：与 `orders.station_id` / `settle_station_id` / `barrel_record.station_id`
--   一致（这些列本来就没有 FK）。加了反而让水站无法清理历史站。
--
-- 影响面：**纯新增 1 张表**，无 UPDATE/DELETE、不动任何存量数据。
-- 执行方式：出错即停止的客户端（mysql CLI 默认，**不要 `--force`**）；
--   本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 回滚：`DROP TABLE inter_station_settlement;` ⚠️ 先回代码再删表。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检（依赖表必须在）
-- -----------------------------------------------------------------------------
SET @deps := (SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('orders','payment_record','staff'));
SELECT IF(@deps = 3, 'OK: orders / payment_record / staff 均在',
          CONCAT('ABORT: 依赖表命中 ', @deps, '/3 —— 先跑 schema.sql')) AS precheck;

SET @abort := IF(@deps <> 3, 'SELECT * FROM __ABORT_V67_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：建表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inter_station_settlement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID（一单一笔，见 uk_inter_settle_order）',
  `from_station_id` bigint NOT NULL COMMENT '付款方 = 归属站（票钱/微信款收在它手上）',
  `to_station_id` bigint NOT NULL COMMENT '收款方 = 结算站 = coalesce(settle_station_id, delivery_station_id, station_id)（谁送谁收）',
  `amount` decimal(10,2) NOT NULL COMMENT '本单应付金额（一律正数；方向由 from→to 表达）',
  `basis` tinyint NOT NULL COMMENT '计价依据: 1本单营收(非票单,水费+配送费+楼层费) 2水票折算实付(默认) 3水票按挂牌价(卖票站可选)',
  `ticket_qty` int NOT NULL DEFAULT '0' COMMENT '本单用票张数（仅 basis=2/3）',
  `unit_price` decimal(10,4) DEFAULT NULL COMMENT '采用的单价快照（仅 basis=2/3）: 2=逐张 ticket_record.unit_price 求和后折算, 3=order_item.price',
  `fee_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '票覆盖的配送费+楼层费（仅 basis=2/3）; ⚠️ v67 不计入 amount —— 「费用要不要一起结」待拍板(docs/design/31 §8.4 第3问), 单独记以便拍板后一个 UPDATE 启用',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1待结清 2已结清 3已冲销(订单取消)',
  `snapshot_time` datetime NOT NULL COMMENT '计价快照时间; 此后批次被消耗/回补都不改本行金额',
  `settled_time` datetime DEFAULT NULL COMMENT '**什么时候算办完**: 付款方登记结清的时间',
  `settled_by` bigint DEFAULT NULL COMMENT '登记结清的人（staff.id）',
  `settle_note` varchar(200) DEFAULT NULL COMMENT '结清凭据说明（转账流水号/经手人）—— 线下动作只留痕, 系统不假装打款',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inter_settle_order` (`order_id`),
  KEY `idx_inter_settle_from` (`from_station_id`,`status`),
  KEY `idx_inter_settle_to` (`to_station_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='站间结算台账(v67): 跨站单的谁欠谁/欠多少/什么时候算办完; 金额由代码算, 本表只存快照, 不参与客户侧对账';

-- -----------------------------------------------------------------------------
-- 第 2 步：自查（期望 1 张表 / 16 列 / 1 个唯一键 / 2 个普通索引 / 0 行）
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inter_station_settlement')  AS table_ready,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inter_station_settlement')  AS column_count,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'inter_station_settlement'
           AND INDEX_NAME = 'uk_inter_settle_order')                            AS uk_order,
       (SELECT COUNT(*) FROM inter_station_settlement)                          AS total_rows;
-- ↑ 期望：table_ready=1, column_count=16, uk_order=1, total_rows=0
