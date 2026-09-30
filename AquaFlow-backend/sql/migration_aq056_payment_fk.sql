-- =============================================================================
-- [AQ-056] payment_record 增加订单外键，杜绝孤儿支付流水再次产生
-- 背景：payment_record 无外键、订单也无物理删除，历史上产生了 45 元孤儿流水
--       (order_id=4/5/6，orders 表最大 id=3)。阶段 1 已清洗；此处补约束防复发。
-- 前置：项目无物理删除 orders 的逻辑（已确认），加外键不会阻塞正常业务。
-- 幂等：先清理残余孤儿，再尝试建约束（**已存在则 skip**，不再报 1061）。
-- ⚠️ 幂等修法（F-07，2026-09-30）：原第 2 步是无条件 `ADD CONSTRAINT` ⇒ 重跑必报 1061。
--    现改为 `information_schema.TABLE_CONSTRAINTS` 预检 + `PREPARE`（与仓内其它迁移同一写法）。
--    预检判据是「payment_record.order_id → orders.id 的外键」，**不依赖约束名**（改名也认得出来）。
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库预检（两个关键表都在才继续；缺表说明连错了库，早失败好过改错库）
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('orders','payment_record'));
SELECT IF(@tbl_cnt = 2,
          'OK: 目标库校验通过（orders / payment_record 均在）',
          CONCAT('ABORT: 关键表命中 ', @tbl_cnt, '/2，请确认连的是 aquaflow 库')) AS precheck;

SET @abort := IF(@tbl_cnt <> 2, 'SELECT * FROM __ABORT_AQ056_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- 1) 清理残余孤儿流水（order_id 非空但订单不存在）—— 二次执行影响 0 行
DELETE FROM payment_record
WHERE order_id IS NOT NULL
  AND order_id NOT IN (SELECT id FROM orders);

-- 2) 建外键（order_id 可空；MySQL 外键不对 NULL 生效，不影响无订单的流水类型）
--    ⚠️ 预检：该列已有指向 orders.id 的外键 ⇒ skip —— 否则重跑报 1061 Duplicate foreign key
SET @fk_exists := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
                   JOIN information_schema.KEY_COLUMN_USAGE kcu
                     ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                    AND kcu.TABLE_NAME        = tc.TABLE_NAME
                    AND kcu.CONSTRAINT_NAME   = tc.CONSTRAINT_NAME
                   WHERE tc.CONSTRAINT_SCHEMA = @db AND tc.TABLE_NAME = 'payment_record'
                     AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
                     AND kcu.COLUMN_NAME = 'order_id' AND kcu.REFERENCED_TABLE_NAME = 'orders');
SELECT IF(@fk_exists = 0,
          '继续: 尚无 payment_record.order_id → orders.id 外键，本次建它',
          'skip: payment_record.order_id 已有指向 orders.id 的外键，无需再建') AS precheck_fk;

SET @s := IF(@fk_exists = 0,
  "ALTER TABLE payment_record ADD CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)",
  "SELECT 'skip: fk_payment_order 已存在' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT 'AQ-056 payment_record 外键迁移完成' AS result;
