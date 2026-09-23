-- =============================================================================
-- V36: 水票档位套餐 + 水票批次单价快照（Phase 1 · P1-D）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v36_ticket_package_and_lot.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17，规格见 docs/design/19）
--
--   1) 水票档位套餐：行业里水票是按「一组多少张、一共多少钱」卖的（10 张 / 20 张 / 100 张
--      越买越便宜）。此前 `inventory.ticket_price` 只有**单张价**这一档，
--      "买 100 张便宜多少"无处表达。
--
--   2) 批次单价快照：档位意味着同一商品的票价是**分段**的（均价随张数变）。
--      于是立刻出现两个问题：站长改了档位价之后，客户账户里已买的票值多少钱？退票按什么价退？
--      —— 桶账早就解决过同一个问题：`customer_barrel_lot.unit_price` 记「买入当时单价」，
--      退押金只认批次单价（"2026 年 30 元买的，2027 年退就退 30 元"）。
--      **水票照抄这套模型**，不另发明一套（`BarrelLedgerService` 文件头的 LotOrigin 就是为此准备的）。
--
--   3) 对账 E8：有了批次，就能像 E3（权益 vs 押金条批次）那样校验
--      「水票余额 == Σ 批次剩余」且「余额的金额价值 == Σ 剩余×批次单价」。
--      没有批次时，客户账户里那 100 张票值多少钱**根本无从校验**。
--
-- 方案
--   · `ticket_package`：站级档位（station_id + product_id + qty 唯一），一行一个档位。
--     **只存定价结构，不是促销引擎** —— 永远可买、不叠加、不互斥，所以不需要活动/优先级/退款摊分那一套。
--   · `ticket_lot`：水票批次（照抄 customer_barrel_lot 的 lot_no / unit_price / remain_qty /
--     source_type / price_source / is_migrated）。
--   · `ticket_account.right_amount`：Σ lot.remain_qty × lot.unit_price（与
--     customer_barrel_asset.right_amount 同构）。
--   · `ticket_record.unit_price` / `ticket_lot_id`：让流水能自证单价来源。
--   · `payment_record.ticket_package_id`：记录"这笔记的是哪个档位" —— 档位价会变，
--     历史流水若只记「买了 100 张、收了 800 元」，几个月后无法自证当时是哪个档位。
--
-- ⚠️ 存量回填（本脚本最重要的一步）
--   已有 `ticket_account` 余额的客户，必须生成对应的批次，否则 E8 一上线就全库报不平。
--   回填批次的单价按「站级水票价 → product.ticket_price → product.price」推断，
--   并标记 `source_type=2 历史迁移` / `price_source=3 当前价推断` / `is_migrated=1`
--   （**单价是推断的**，退票时需二次确认）。
--
-- 幂等：可重复执行（表/列/唯一键均查 information_schema；回填按「该客户该商品尚无批次」过滤）。
-- 回滚：DROP TABLE ticket_lot; DROP TABLE ticket_package;
--       ALTER TABLE ticket_account DROP COLUMN right_amount;
--       ALTER TABLE ticket_record DROP COLUMN ticket_lot_id, DROP COLUMN unit_price;
--       ALTER TABLE payment_record DROP COLUMN ticket_package_id;
--       （回滚只失去"档位定价与批次单价"，不影响任何水票余额、押金、桶账、订单金额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('ticket_account','ticket_record','payment_record','inventory','product'));
SET @s := IF(@t=5,
  "SELECT '开始执行 V36（水票档位 + 批次单价快照）' AS note",
  "SELECT 'ABORT: ticket_account/ticket_record/payment_record/inventory/product 未全部存在，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) ticket_package：站级档位
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_package')=0,
  "CREATE TABLE ticket_package (
     id bigint NOT NULL AUTO_INCREMENT,
     station_id bigint NOT NULL COMMENT '水站ID（档位是站级的，不是全局的）',
     product_id bigint NOT NULL COMMENT '商品ID',
     qty int NOT NULL COMMENT '本档张数（10 / 20 / 100）',
     price decimal(10,2) NOT NULL COMMENT '本档总价',
     unit_price decimal(10,2) NOT NULL COMMENT '均价 = price / qty（冗余落库，用于快照与展示，避免每次相除）',
     title varchar(50) DEFAULT NULL COMMENT '展示名（如「100 张超值装」），可空则前端按张数生成',
     status tinyint NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
     sort int NOT NULL DEFAULT 0 COMMENT '排序（小的在前）',
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (id),
     UNIQUE KEY uk_ticket_package (station_id, product_id, qty),
     KEY idx_ticket_package_station_product (station_id, product_id, status)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水票档位套餐(站级定价结构, 非促销引擎)'",
  "SELECT 'skip: ticket_package 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) ticket_lot：水票批次（照抄 customer_barrel_lot 的模型）
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_lot')=0,
  "CREATE TABLE ticket_lot (
     id bigint NOT NULL AUTO_INCREMENT,
     lot_no varchar(32) NOT NULL COMMENT '批次号 TMyyyymmdd-000001（拿到自增 id 后生成，与押金条 DP 同款做法）',
     customer_id bigint NOT NULL,
     station_id bigint NOT NULL,
     product_id bigint NOT NULL,
     unit_price decimal(10,2) NOT NULL COMMENT '买入当时单价快照（档位均价）—— 将来退票按它退，不按退时的当前价',
     qty int NOT NULL COMMENT '本批张数',
     remain_qty int NOT NULL COMMENT '剩余未退张数',
     source_type tinyint NOT NULL DEFAULT 1 COMMENT '1 在线购买 2 历史迁移 3 人工补录 4 退款回补',
     price_source tinyint NOT NULL DEFAULT 1 COMMENT '1 实付均价 2 当时站级水票价 3 当前价推断(兜底)',
     is_migrated tinyint NOT NULL DEFAULT 0 COMMENT '1=历史迁移/单价为推断，退票需二次确认',
     payment_record_id bigint DEFAULT NULL COMMENT '来源支付流水（在线购买）',
     status tinyint NOT NULL DEFAULT 1 COMMENT '1 有效 2 已退完 3 作废',
     operator_id bigint DEFAULT NULL,
     note varchar(200) DEFAULT NULL,
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (id),
     UNIQUE KEY uk_ticket_lot_no (lot_no),
     KEY idx_ticket_lot_owner (customer_id, station_id, product_id, status)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='水票批次(单价快照); 余额的真相源是 Σ remain_qty'",
  "SELECT 'skip: ticket_lot 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) ticket_account.right_amount
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_account' AND COLUMN_NAME='right_amount')=0,
  "ALTER TABLE ticket_account ADD COLUMN right_amount decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '剩余水票的金额价值 = Σ ticket_lot.remain_qty × unit_price（派生值，真相源是 ticket_lot）'",
  "SELECT 'skip: ticket_account.right_amount 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) ticket_record.unit_price / ticket_lot_id
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record' AND COLUMN_NAME='unit_price')=0,
  "ALTER TABLE ticket_record ADD COLUMN unit_price decimal(10,2) DEFAULT NULL COMMENT '本次变动的单价（购买=实付均价；消耗=所消耗批次的加权均价；退款=回补批次的单价）'",
  "SELECT 'skip: ticket_record.unit_price 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='ticket_record' AND COLUMN_NAME='ticket_lot_id')=0,
  "ALTER TABLE ticket_record ADD COLUMN ticket_lot_id bigint DEFAULT NULL COMMENT '关联的水票批次（仅当本次变动只涉及一个批次时有值；跨批次时为 NULL，看 unit_price 的加权均价）'",
  "SELECT 'skip: ticket_record.ticket_lot_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5) payment_record.ticket_package_id
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='payment_record' AND COLUMN_NAME='ticket_package_id')=0,
  "ALTER TABLE payment_record ADD COLUMN ticket_package_id bigint DEFAULT NULL COMMENT '在线购票：所购档位（ticket_package.id）。档位价会变，历史流水必须能自证当时是哪个档位'",
  "SELECT 'skip: payment_record.ticket_package_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 6) 存量回填：给已有余额的账户生成批次
--    单价推断阶梯：站级水票价（inventory.ticket_price）→ product.ticket_price → product.price
--    （与 util/PriceUtil.calcUnitPrice 的阶梯一致；这里用 SQL 复刻，因为 PriceUtil 是 Java 侧）
INSERT INTO ticket_lot(lot_no, customer_id, station_id, product_id, unit_price, qty, remain_qty,
                       source_type, price_source, is_migrated, status, note, create_time, update_time)
SELECT CONCAT('TK-MIG-', a.id), a.customer_id, a.station_id, a.product_id,
       COALESCE(NULLIF(i.ticket_price, 0), NULLIF(p.ticket_price, 0), p.price, 0.00),
       a.remain_quantity, a.remain_quantity, 2, 3, 1, 1,
       'V36 存量回填：单价为推断值，退票需二次确认', NOW(), NOW()
  FROM ticket_account a
  LEFT JOIN inventory i ON i.station_id = a.station_id AND i.product_id = a.product_id
  LEFT JOIN product   p ON p.id = a.product_id
 WHERE a.remain_quantity > 0
   AND NOT EXISTS (SELECT 1 FROM ticket_lot l
                    WHERE l.customer_id = a.customer_id AND l.station_id = a.station_id
                      AND l.product_id = a.product_id);

-- 7) 同步 right_amount（派生列，必须以批次为准）
UPDATE ticket_account a
  LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty * unit_price) AS ra
               FROM ticket_lot WHERE status = 1
              GROUP BY customer_id, station_id, product_id) l
    ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id
   SET a.right_amount = COALESCE(l.ra, 0.00);

-- 8) 校验
SELECT 'V36 完成：水票档位 + 批次单价快照已就绪' AS note;

SELECT '校验B：E8 数量等式（余额 == Σ 批次剩余；应为 0 条不平）' AS check_item;
SELECT COUNT(*) AS e8_quantity_unbalanced FROM (
  SELECT a.customer_id FROM ticket_account a
    LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty) rq FROM ticket_lot WHERE status=1
                GROUP BY customer_id, station_id, product_id) l
      ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id
   WHERE COALESCE(a.remain_quantity,0) <> COALESCE(l.rq,0)) x;

SELECT '校验C：E8 金额等式（right_amount == Σ 剩余×批次单价；应为 0 条不平）' AS check_item;
SELECT COUNT(*) AS e8_amount_unbalanced FROM (
  SELECT a.customer_id FROM ticket_account a
    LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty * unit_price) ra FROM ticket_lot WHERE status=1
                GROUP BY customer_id, station_id, product_id) l
      ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id
   WHERE ABS(COALESCE(a.right_amount,0) - COALESCE(l.ra,0)) > 0.009) x;

SELECT '校验D：存量余额已全部有批次覆盖（应相等）' AS check_item;
SELECT (SELECT COUNT(*) FROM ticket_account WHERE remain_quantity > 0) AS accounts_with_balance,
       (SELECT COUNT(DISTINCT CONCAT(customer_id,'-',station_id,'-',product_id)) FROM ticket_lot) AS lots_owners;
