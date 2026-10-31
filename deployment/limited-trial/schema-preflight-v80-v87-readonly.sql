-- AquaFlow current v80-v87 metadata preflight. READ ONLY: no USE, SET, DDL or data writes.
-- Run only after the operator independently confirms the actual target connection/catalog.
-- Missing/mismatched rows require review against sql/README.md; this is not migration authorization.
-- Generated expressions are deliberately printed for manual comparison with the runtime schema guard.
-- This does not attest that the cloud schema, migration history, pricing or application startup passed.
SELECT DATABASE() AS selected_database, @@hostname AS server_host, @@port AS server_port, VERSION() AS mysql_version;

WITH expected_tables AS (
SELECT 'historical_asset_claim' AS table_name
UNION ALL
SELECT 'customer_refusal_freeze_scope'
UNION ALL
SELECT 'barrel_return_payout'
UNION ALL
SELECT 'station_aftercare'
UNION ALL
SELECT 'station_aftercare_action'
UNION ALL
SELECT 'payment_original_success'
UNION ALL
SELECT 'payment_original_refund_hold'
UNION ALL
SELECT 'historical_asset_claim_photo'
UNION ALL
SELECT 'barrel_purchase_refund'
UNION ALL
SELECT 'order_barrel_refund'
UNION ALL
SELECT 'customer_refusal_action'
UNION ALL
SELECT 'inventory'
UNION ALL
SELECT 'inventory_record'
)
SELECT CASE WHEN t.table_name IS NULL THEN 'MISSING' WHEN t.engine <> 'InnoDB' THEN 'MISMATCH' ELSE 'PASS' END AS result,
 e.table_name, t.engine FROM expected_tables e LEFT JOIN information_schema.tables t
 ON t.table_schema=DATABASE() AND t.table_name=e.table_name ORDER BY e.table_name;

WITH expected_columns AS (
SELECT 'historical_asset_claim' AS table_name, 'id' AS column_name, 'bigint' AS column_type, 'NO' AS is_nullable, NULL AS collation_name, 'auto_increment' AS extra
UNION ALL
SELECT 'historical_asset_claim', 'customer_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'historical_asset_claim', 'station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'historical_asset_claim', 'client_token', 'varchar(64)', 'NO', 'utf8mb4_bin', ''
UNION ALL
SELECT 'historical_asset_claim', 'request_digest', 'varchar(64)', 'NO', 'utf8mb4_bin', ''
UNION ALL
SELECT 'historical_asset_claim', 'request_json', 'json', 'NO', NULL, ''
UNION ALL
SELECT 'historical_asset_claim', 'status', 'varchar(16)', 'NO', 'utf8mb4_bin', ''
UNION ALL
SELECT 'historical_asset_claim', 'reviewer_id', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'historical_asset_claim', 'review_note', 'varchar(500)', 'YES', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'historical_asset_claim', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'historical_asset_claim', 'review_time', 'datetime', 'YES', NULL, ''
UNION ALL
SELECT 'historical_asset_claim', 'active_customer_id', 'bigint', 'YES', NULL, 'STORED GENERATED'
UNION ALL
SELECT 'customer_refusal_freeze_scope', 'order_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'customer_refusal_freeze_scope', 'product_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'customer_refusal_freeze_scope', 'amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'id', 'bigint', 'NO', NULL, 'auto_increment'
UNION ALL
SELECT 'barrel_return_payout', 'record_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'actor_key', 'varchar(80)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'barrel_return_payout', 'request_key', 'varchar(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'barrel_return_payout', 'request_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'barrel_return_payout', 'amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'payer_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'final_payout', 'tinyint', 'NO', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'barrel_return_payout', 'recorded_epoch_second', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'barrel_return_payout', 'recorded_nano', 'int', 'YES', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'refund_payment_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'order_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'refund_station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'other_station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'refund_amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'proposed_amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'proposed_station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'proposed_by', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'reason', 'varchar(1000)', 'NO', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'station_aftercare', 'status', 'varchar(16)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'station_aftercare', 'version', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'agreed_by', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'agreed_time', 'datetime', 'YES', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'paid_by', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'paid_time', 'datetime', 'YES', NULL, ''
UNION ALL
SELECT 'station_aftercare', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'station_aftercare', 'update_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED on update CURRENT_TIMESTAMP'
UNION ALL
SELECT 'station_aftercare_action', 'id', 'bigint', 'NO', NULL, 'auto_increment'
UNION ALL
SELECT 'station_aftercare_action', 'refund_payment_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare_action', 'actor_key', 'varchar(80)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'station_aftercare_action', 'request_key', 'varchar(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'station_aftercare_action', 'request_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'station_aftercare_action', 'action', 'varchar(16)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'station_aftercare_action', 'amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare_action', 'reason', 'varchar(1000)', 'NO', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'station_aftercare_action', 'from_version', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare_action', 'to_version', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'station_aftercare_action', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'payment_original_success', 'payment_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'customer_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'order_id', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'payer_total_cents', 'int', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'binding_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_success', 'transaction_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_success', 'request_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_success', 'success_epoch_second', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'success_nano', 'int', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_success', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'payment_original_refund_hold', 'payment_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_refund_hold', 'request_key', 'varchar(128)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'binding_reference', 'varchar(128)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'binding_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'original_transaction_reference', 'varchar(128)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'original_amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_refund_hold', 'amount', 'decimal(10,2)', 'NO', NULL, ''
UNION ALL
SELECT 'payment_original_refund_hold', 'currency', 'char(3)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'request_digest', 'char(64)', 'NO', 'ascii_bin', ''
UNION ALL
SELECT 'payment_original_refund_hold', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'historical_asset_claim_photo', 'customer_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'historical_asset_claim_photo', 'station_id', 'bigint', 'NO', NULL, ''
UNION ALL
SELECT 'historical_asset_claim_photo', 'client_token', 'varchar(64)', 'NO', 'utf8mb4_bin', ''
UNION ALL
SELECT 'historical_asset_claim_photo', 'object_name', 'varchar(512)', 'NO', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'historical_asset_claim_photo', 'create_time', 'datetime', 'NO', NULL, 'DEFAULT_GENERATED'
UNION ALL
SELECT 'barrel_purchase_refund', 'payout_id', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'barrel_purchase_refund', 'legacy_return_record_id', 'bigint', 'YES', NULL, 'STORED GENERATED'
UNION ALL
SELECT 'order_barrel_refund', 'payout_id', 'bigint', 'YES', NULL, ''
UNION ALL
SELECT 'order_barrel_refund', 'legacy_return_record_id', 'bigint', 'YES', NULL, 'STORED GENERATED'
UNION ALL
SELECT 'customer_refusal_action', 'scope_json', 'longtext', 'YES', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'inventory', 'pricing_confirmed', 'tinyint', 'NO', NULL, ''
UNION ALL
SELECT 'inventory', 'image_object_name_override', 'varchar(512)', 'YES', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'inventory', 'cost_amount', 'decimal(18,2)', 'YES', NULL, ''
UNION ALL
SELECT 'inventory_record', 'cost_amount', 'decimal(18,2)', 'YES', NULL, ''
)
SELECT CASE WHEN c.column_name IS NULL THEN 'MISSING'
 WHEN NOT (c.column_type <=> e.column_type) OR NOT (c.is_nullable <=> e.is_nullable)
 OR NOT (c.collation_name <=> e.collation_name) OR NOT (c.extra <=> e.extra) THEN 'MISMATCH' ELSE 'PASS' END AS result,
 e.table_name,e.column_name,e.column_type AS expected_type,c.column_type AS actual_type,
 e.is_nullable AS expected_nullable,c.is_nullable AS actual_nullable,
 e.collation_name AS expected_collation,c.collation_name AS actual_collation,e.extra AS expected_extra,c.extra AS actual_extra
 FROM expected_columns e LEFT JOIN information_schema.columns c
 ON c.table_schema=DATABASE() AND c.table_name=e.table_name AND c.column_name=e.column_name
 ORDER BY e.table_name,e.column_name;

WITH expected_indices AS (
SELECT 'historical_asset_claim' AS table_name, 'idx_asset_claim_pending' AS index_name, 1 AS non_unique, 'station_id,status,id' AS columns_ordered, 0 AS prefixed
UNION ALL
SELECT 'historical_asset_claim', 'PRIMARY', 0, 'id', 0
UNION ALL
SELECT 'historical_asset_claim', 'uk_asset_claim_once', 0, 'station_id,active_customer_id', 0
UNION ALL
SELECT 'historical_asset_claim', 'uk_asset_claim_token', 0, 'customer_id,client_token', 0
UNION ALL
SELECT 'customer_refusal_freeze_scope', 'PRIMARY', 0, 'order_id,product_id', 0
UNION ALL
SELECT 'barrel_return_payout', 'PRIMARY', 0, 'id', 0
UNION ALL
SELECT 'barrel_return_payout', 'uk_return_payout_key', 0, 'record_id,request_key', 0
UNION ALL
SELECT 'station_aftercare', 'idx_aftercare_order', 1, 'order_id', 0
UNION ALL
SELECT 'station_aftercare', 'PRIMARY', 0, 'refund_payment_id', 0
UNION ALL
SELECT 'station_aftercare_action', 'PRIMARY', 0, 'id', 0
UNION ALL
SELECT 'station_aftercare_action', 'uk_aftercare_action', 0, 'refund_payment_id,actor_key,request_key', 0
UNION ALL
SELECT 'payment_original_success', 'PRIMARY', 0, 'payment_id', 0
UNION ALL
SELECT 'payment_original_success', 'uk_original_success_tx', 0, 'binding_digest,transaction_digest', 0
UNION ALL
SELECT 'payment_original_refund_hold', 'PRIMARY', 0, 'payment_id,request_key', 0
UNION ALL
SELECT 'payment_original_refund_hold', 'uk_original_refund_request', 0, 'binding_digest,request_key', 0
UNION ALL
SELECT 'historical_asset_claim_photo', 'PRIMARY', 0, 'customer_id,client_token', 0
UNION ALL
SELECT 'barrel_purchase_refund', 'uk_purchase_return', 0, 'purchase_id,legacy_return_record_id', 0
UNION ALL
SELECT 'barrel_purchase_refund', 'uk_return_payout_source', 0, 'purchase_id,return_record_id,payout_id', 0
UNION ALL
SELECT 'order_barrel_refund', 'uk_order_barrel_return', 0, 'purchase_id,legacy_return_record_id', 0
UNION ALL
SELECT 'order_barrel_refund', 'uk_return_payout_source', 0, 'purchase_id,return_record_id,payout_id', 0
)
SELECT CASE WHEN s.index_name IS NULL THEN 'MISSING'
 WHEN NOT (s.non_unique <=> e.non_unique) OR NOT (s.columns_ordered <=> e.columns_ordered)
 OR NOT (s.prefixed <=> e.prefixed) THEN 'MISMATCH' ELSE 'PASS' END AS result,
 e.table_name,e.index_name,e.non_unique AS expected_non_unique,s.non_unique AS actual_non_unique,
 e.columns_ordered AS expected_columns,s.columns_ordered AS actual_columns,s.prefixed AS actual_prefix_parts
 FROM expected_indices e LEFT JOIN
 (SELECT table_name,index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS columns_ordered,
 SUM(sub_part IS NOT NULL) AS prefixed FROM information_schema.statistics WHERE table_schema=DATABASE()
 GROUP BY table_name,index_name,non_unique) s
 ON s.table_name=e.table_name AND s.index_name=e.index_name ORDER BY e.table_name,e.index_name;

WITH expected_generated AS (
SELECT 'historical_asset_claim' AS table_name, 'active_customer_id' AS column_name, '(case when (`status` in (_utf8mb4\\''PENDING\\'',_utf8mb4\\''APPROVED\\'')) then `customer_id` else NULL end)' AS reference_expression
UNION ALL
SELECT 'barrel_purchase_refund', 'legacy_return_record_id', 'if((`payout_id` is null),`return_record_id`,NULL)'
UNION ALL
SELECT 'order_barrel_refund', 'legacy_return_record_id', 'if((`payout_id` is null),`return_record_id`,NULL)'
)
SELECT 'REVIEW_EXPRESSION' AS result,e.table_name,e.column_name,e.reference_expression,
 c.generation_expression AS actual_expression,c.extra AS actual_storage
 FROM expected_generated e LEFT JOIN information_schema.columns c
 ON c.table_schema=DATABASE() AND c.table_name=e.table_name AND c.column_name=e.column_name
 ORDER BY e.table_name,e.column_name;
