-- v91：同一原购票款允许多笔剩余票退出。
--
-- ⚠️ 执行前置（缺一不可）：
--   1. 先逐句执行 precheck_v91_ticket_exit_multiple_refunds.sql；
--   2. 只有 v91_precheck_result = 'GO: exact v90 old structure...' 才继续；
--   3. 停止写入该表（及依赖退票凭据的后端），完成可恢复备份（mysqldump）；
--   4. 记录 precheck 的 existing_rows，升级后用 verify 脚本核对行数一致；
--   5. 任何 STOP、缺列、索引名/列序不同、未知或部分迁移结构：停下人工核实，不得猜修。
--
-- 本文件故意只保留一条 ALTER：
--   · 不使用 session 变量，不使用 PREPARE，不依赖上一条语句的执行结果；
--   · 仅在精确 v90 旧结构上执行；
--   · 不 UPDATE/DELETE/重写任何历史金额或票数；只增加新列、主键和索引。
--
-- 迁移后的结构：
--   id BIGINT NOT NULL AUTO_INCREMENT 主键；
--   original_payment_id 普通索引（一原款多笔退出）；
--   idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL；
--   uk_ticket_exit_request(original_payment_id, idempotency_key)；
--   既有 refund_payment_id 唯一键保持不变。
ALTER TABLE ticket_exit_refund
  DROP PRIMARY KEY,
  ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
  ADD COLUMN idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER refund_payment_id,
  ADD PRIMARY KEY (id),
  ADD UNIQUE KEY uk_ticket_exit_request (original_payment_id, idempotency_key),
  ADD KEY idx_ticket_exit_original (original_payment_id);
