-- v75：复用反馈存储追加退款说明。仅增可空列和索引，不改历史内容/身份/金额/状态。
-- 当前任务只允许在已确认的隔离库验证；真实库执行前须核准完整目标并备份。
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='refund_type')=0,
 'ALTER TABLE feedback ADD COLUMN refund_type varchar(24) DEFAULT NULL COMMENT ''退款关联类型；普通反馈为空''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='refund_id')=0,
 'ALTER TABLE feedback ADD COLUMN refund_id bigint DEFAULT NULL COMMENT ''原款/退押金申请ID''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='responsible_station_id')=0,
 'ALTER TABLE feedback ADD COLUMN responsible_station_id bigint DEFAULT NULL COMMENT ''服务端判权责任站''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='actor_key')=0,
 'ALTER TABLE feedback ADD COLUMN actor_key varchar(40) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''服务端作者身份''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='idempotency_key')=0,
 'ALTER TABLE feedback ADD COLUMN idempotency_key varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''关联说明请求键''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='feedback' AND column_name='request_digest')=0,
 'ALTER TABLE feedback ADD COLUMN request_digest varchar(64) DEFAULT NULL COMMENT ''说明及联系方式指纹''','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='feedback' AND index_name='uk_feedback_refund_note')=0,
 'ALTER TABLE feedback ADD UNIQUE KEY uk_feedback_refund_note(actor_key,refund_type,refund_id,idempotency_key)','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='feedback' AND index_name='idx_feedback_refund_station')=0,
 'ALTER TABLE feedback ADD KEY idx_feedback_refund_station(responsible_station_id,refund_type,refund_id)','SELECT 1');
PREPARE aq_v75 FROM @ddl; EXECUTE aq_v75; DEALLOCATE PREPARE aq_v75;
