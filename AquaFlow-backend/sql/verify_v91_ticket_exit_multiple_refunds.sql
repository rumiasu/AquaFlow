-- v91 只读核验（DMC 可逐句执行；不写库、不使用 session 变量、不使用 PREPARE）。
-- 在 migration_v91_ticket_exit_multiple_refunds.sql 成功后执行。
-- 行数必须与 precheck_v91_ticket_exit_multiple_refunds.sql 记录的 existing_rows 一致。

SELECT DATABASE() AS target_database;

-- 1. 列：类型 / nullable / key / auto_increment / 字符集 / 顺序
SELECT ordinal_position, column_name, column_type, is_nullable, column_key, extra,
       character_set_name, collation_name, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
ORDER BY ordinal_position;

-- 2. 索引：主键、唯一键、普通索引及列顺序
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, collation, index_type
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
ORDER BY index_name, seq_in_index;

-- 3. 升级后行数（必须等于 precheck 的 existing_rows）
SELECT COUNT(*) AS rows_after
FROM ticket_exit_refund;

-- 4. 严格判定：结构必须完全符合 v91；否则 STOP，人工核实，不猜修
SELECT CASE WHEN
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND table_type = 'BASE TABLE' AND engine = 'InnoDB') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund') = 8
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'id' AND column_type = 'bigint' AND is_nullable = 'NO'
      AND extra = 'auto_increment') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'original_payment_id' AND column_type = 'bigint' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'refund_payment_id' AND column_type = 'bigint' AND is_nullable = 'NO') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND column_name = 'idempotency_key' AND column_type = 'varchar(64)' AND is_nullable = 'YES'
      AND character_set_name = 'ascii' AND collation_name = 'ascii_bin') = 1
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
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'PRIMARY' AND non_unique = 0
      AND column_name = 'id' AND seq_in_index = 1
      AND sub_part IS NULL AND index_type = 'BTREE' AND collation = 'A') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'PRIMARY') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'idx_ticket_exit_original' AND non_unique = 1
      AND column_name = 'original_payment_id' AND seq_in_index = 1
      AND sub_part IS NULL AND index_type = 'BTREE' AND collation = 'A') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'idx_ticket_exit_original') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'uk_ticket_exit_request' AND non_unique = 0
      AND column_name = 'original_payment_id' AND seq_in_index = 1
      AND sub_part IS NULL AND index_type = 'BTREE' AND collation = 'A') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'uk_ticket_exit_request' AND non_unique = 0
      AND column_name = 'idempotency_key' AND seq_in_index = 2
      AND sub_part IS NULL AND index_type = 'BTREE' AND collation = 'A') = 1
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ticket_exit_refund'
      AND index_name = 'uk_ticket_exit_request') = 2
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
      AND index_name <> 'PRIMARY' AND non_unique = 0) = 2
THEN 'GO: v91 structure verified; existing row count must equal precheck existing_rows'
ELSE 'STOP: v91 structure is incomplete or unknown; do not continue, inspect manually'
END AS v91_verify_result;
