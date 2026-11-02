-- Only SELECT; choose the already verified database first.
SELECT DATABASE() AS selected_database, @@server_uuid AS server_uuid;
SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='case_handling_action';
SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='case_handling_action' ORDER BY ordinal_position;
SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='case_handling_action' ORDER BY index_name,seq_in_index;
