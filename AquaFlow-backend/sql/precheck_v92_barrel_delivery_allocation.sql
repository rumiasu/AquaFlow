-- Read only. Confirm intended server/catalog and recoverable backup before any separate DDL.
SELECT DATABASE() AS database_name, @@hostname AS server_hostname, @@port AS server_port;
SELECT table_name, engine, table_comment FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name IN ('barrel_delivery_receipt','barrel_return_delivery_allocation');
SELECT table_name, column_name, column_type, is_nullable, column_default, collation_name
 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name IN ('barrel_delivery_receipt','barrel_return_delivery_allocation') ORDER BY table_name,ordinal_position;
SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part
 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name IN ('barrel_delivery_receipt','barrel_return_delivery_allocation') ORDER BY table_name,index_name,seq_in_index;
