-- Read only. Run on the explicitly confirmed target, after v89 and recoverable backup.
SELECT DATABASE() AS target_schema, VERSION() AS mysql_version;
-- Compare the actual displayed time with Shanghai; SYSTEM alone does not identify its zone.
SELECT NOW() AS database_time, @@session.time_zone AS session_time_zone, @@global.time_zone AS global_time_zone;
SELECT table_name FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN
 ('case_handling_action','order_ticket_payment','order_ticket_hold','ticket_delivery_offer','ticket_delivery_benefit')
 ORDER BY table_name;
-- Before v90, four ticket tables may be absent. If present, verify their exact columns;
-- CREATE IF NOT EXISTS does not repair a manually created incompatible table.
SELECT table_name,column_name,column_type,is_nullable,column_default
 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name IN ('order_ticket_payment','order_ticket_hold','ticket_delivery_offer','ticket_delivery_benefit')
 ORDER BY table_name,ordinal_position;
SELECT index_name,non_unique,column_name FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='payment_record' AND index_name='uk_payment_active_order'
 ORDER BY seq_in_index;
-- Old open test orders are a review list, not a command to add charges or delete records.
SELECT id,station_id,payment_method,payment_status,status,water_amount,deposit_amount,delivery_fee,floor_fee
 FROM orders WHERE payment_method=3 AND status IN (1,2) ORDER BY id LIMIT 100;
