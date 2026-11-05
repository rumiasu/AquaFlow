-- v91 只读预检（DMC 可逐句执行；本文件不写库、不使用 session 变量、不使用 PREPARE）。
-- 目标库必须是即将升级的同一个库；先执行 SELECT DATABASE() 核对。
-- 只有 v91_precheck_result 返回 GO，才允许执行 migration_v91_ticket_exit_multiple_refunds.sql。
-- 任何 STOP、缺表、列/索引与下面诊断不一致，都必须停下人工核实，不得猜修或直接跑 ALTER。

SELECT DATABASE() AS target_database;

-- 1. 表是否存在、引擎是什么
SELECT table_name, table_type, engine, table_collation
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund';

-- 2. 逐列核对类型 / nullable / key / auto_increment / 字符集
SELECT ordinal_position, column_name, column_type, is_nullable, column_key, extra,
       character_set_name, collation_name, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
ORDER BY ordinal_position;

-- 3. 逐索引核对主键、唯一键、列顺序
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, collation, index_type
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
ORDER BY index_name, seq_in_index;

-- 4. 现有数据量（升级前后必须一致；本脚本不改写任何行）
SELECT COUNT(*) AS existing_rows
FROM ticket_exit_refund;

-- 5. 严格判定：只有精确的 v90 旧结构才返回 GO
-- 旧结构必须同时满足：
--   6 列；original_payment_id 主键；refund_payment_id 唯一；无 id、无 idempotency_key；
--   无 idx_ticket_exit_original、无 uk_ticket_exit_request；所有旧列类型/可空性完全匹配。
SELECT CASE WHEN
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND table_type = 'BASE TABLE' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund') = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'original_payment_id' AND column_type = 'bigint' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'refund_payment_id' AND column_type = 'bigint' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'quantity' AND column_type = 'int' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'amount' AND column_type = 'decimal(10,2)' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'operator_id' AND column_type = 'bigint' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'create_time' AND column_type = 'datetime' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND (column_name IN ('id','idempotency_key') OR extra <> '')) = 0
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'PRIMARY' AND non_unique = 0
      AND column_name = 'original_payment_id' AND seq_in_index = 1
      AND sub_part IS NULL AND index_type = 'BTREE' AND collation = 'A') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'PRIMARY') = 1
  AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name <> 'PRIMARY' AND non_unique = 0) = 1
  -- refund_payment_id 必须是单列唯一索引（拒绝复合、前缀、HASH/降序等变体）
  AND (SELECT COUNT(*) FROM (
    SELECT index_name, COUNT(*) AS cols, MIN(non_unique) AS nu,
           MAX(IFNULL(sub_part, 0)) AS prefix_or_zero,
           MIN(index_type) AS idx_type, MIN(collation) AS idx_collation,
           MAX(column_name) AS only_column
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name <> 'PRIMARY'
    GROUP BY index_name
    HAVING cols = 1 AND nu = 0 AND prefix_or_zero = 0
       AND idx_type = 'BTREE' AND idx_collation = 'A'
       AND only_column = 'refund_payment_id'
  ) refund_unique) = 1
  AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name <> 'PRIMARY') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name IN ('idx_ticket_exit_original','uk_ticket_exit_request')) = 0
THEN 'GO: exact v90 old structure; stop writes, back up, run the single ALTER'
ELSE 'STOP: structure is not the exact v90 old structure; do not run ALTER'
END AS v91_precheck_result;
