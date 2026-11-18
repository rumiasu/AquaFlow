-- 地址编辑、删除之后仍可辨认原请求。回执仅记摘要和标识。
CREATE TABLE IF NOT EXISTS address_create_receipt (
  customer_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest CHAR(64) CHARACTER SET ascii NOT NULL,
  address_id BIGINT NOT NULL,
  PRIMARY KEY (customer_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新增地址回执，保留原请求身份';
