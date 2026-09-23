-- =============================================================================
-- [AQ-056] payment_record 增加订单外键，杜绝孤儿支付流水再次产生
-- 背景：payment_record 无外键、订单也无物理删除，历史上产生了 45 元孤儿流水
--       (order_id=4/5/6，orders 表最大 id=3)。阶段 1 已清洗；此处补约束防复发。
-- 前置：项目无物理删除 orders 的逻辑（已确认），加外键不会阻塞正常业务。
-- 幂等：先清理残余孤儿，再尝试建约束（已存在则忽略）。
-- =============================================================================

-- 1) 清理残余孤儿流水（order_id 非空但订单不存在）
DELETE FROM payment_record
WHERE order_id IS NOT NULL
  AND order_id NOT IN (SELECT id FROM orders);

-- 2) 建外键（order_id 可空；MySQL 外键不对 NULL 生效，不影响无订单的流水类型）
--    若约束已存在会报 1061，可忽略。
ALTER TABLE payment_record
  ADD CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id);

SELECT 'AQ-056 payment_record 外键迁移完成' AS result;
