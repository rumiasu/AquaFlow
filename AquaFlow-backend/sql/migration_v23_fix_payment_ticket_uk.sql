-- =============================================================================
-- migration_v23_fix_payment_ticket_uk.sql
--
-- 目的（Phase C/D · DEF-3）：修复「取消已付款订单」必然失败的两个唯一键冲突。
--
--   1) ticket_record.uk_ticket_consume(order_id, product_id)
--      取消水票已付订单时，refundTicket 要插入一条 source='退款' 的回补流水，
--      而 consumeTicket 已插入同 (order_id, product_id) 的 source='消费' 消费流水，
--      实测报 Duplicate entry 'N-M' for key 'ticket_record.uk_ticket_consume'。
--      修复：唯一键纳入 source → 消费/退款各一条互不冲突；
--            "消费"维度仍唯一，仍能兜底并发双扣。
--
--   2) payment_record.uk_payment_order_status(order_id, status)
--      该唯一键与「退款另立负金额冲正流水」的设计根本冲突：
--      refundOrder 先把原 PAID 流水置为 REFUNDED，再插入一条 REFUNDED 冲正流水，
--      (order_id, 已退款) 必然重复 → 紧接着第二次失败。
--      修复：降级为普通索引 idx_payment_order_status(order_id, status)，
--            保留查询性能；防重责任回到应用层
--            （PaymentServiceImpl.createPayment 已有 PAID/PENDING 流水即直接返回）。
--
-- 幂等性：MySQL 8.4 无 DROP INDEX IF EXISTS，故统一用 information_schema 预检 + PREPARE，
--         可重复执行；已修好的库再次执行只会打印 skip 提示。
--
-- 使用方式（务必指定库名，脚本内 DATABASE() 取当前库）：
--     mysql -uroot <库名> < migration_v23_fix_payment_ticket_uk.sql
--
-- 回滚策略：本迁移只调整索引，不改数据、不删列。
--   - 如需回滚 1) ：需先确认不存在 (order_id, product_id) 重复行（消费+退款成对即有重复），
--                   否则无法恢复两列唯一键——即"回滚"会与业务冲突，属不可逆。
--   - 如需回滚 2) ：ALTER TABLE payment_record ADD UNIQUE KEY uk_payment_order_status(order_id, status);
--                   但只有在该订单不存在多条同状态流水时才可执行。
-- 对已有数据的影响：无（仅索引结构变化）。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) payment_record：删除冲突的唯一键，补普通索引
-- ---------------------------------------------------------------------------

SET @db := DATABASE();

SET @cnt := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = @db AND table_name = 'payment_record'
               AND index_name = 'uk_payment_order_status');
SET @sql := IF(@cnt > 0,
    'ALTER TABLE payment_record DROP INDEX uk_payment_order_status',
    'SELECT ''skip: payment_record.uk_payment_order_status 不存在，无需删除'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @cnt := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = @db AND table_name = 'payment_record'
               AND index_name = 'idx_payment_order_status');
SET @sql := IF(@cnt = 0,
    'ALTER TABLE payment_record ADD INDEX idx_payment_order_status (order_id, status)',
    'SELECT ''skip: payment_record.idx_payment_order_status 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) ticket_record：唯一键纳入 source
-- ---------------------------------------------------------------------------

SET @cols := (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
              FROM information_schema.statistics
              WHERE table_schema = @db AND table_name = 'ticket_record'
                AND index_name = 'uk_ticket_consume');

SET @sql := IF(@cols IS NULL,
    'ALTER TABLE ticket_record ADD UNIQUE KEY uk_ticket_consume (order_id, product_id, source)',
    IF(@cols = 'order_id,product_id',
       'ALTER TABLE ticket_record DROP INDEX uk_ticket_consume, ADD UNIQUE KEY uk_ticket_consume (order_id, product_id, source)',
       'SELECT ''skip: ticket_record.uk_ticket_consume 已包含 source，无需调整'' AS note'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) 结果自检
-- ---------------------------------------------------------------------------

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols, non_unique
FROM information_schema.statistics
WHERE table_schema = @db
  AND ((table_name = 'payment_record' AND index_name IN ('uk_payment_order_status', 'idx_payment_order_status'))
    OR (table_name = 'ticket_record'   AND index_name = 'uk_ticket_consume'))
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
