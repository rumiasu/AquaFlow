-- v92: exact delivery receipt provenance and explicit return allocation.
-- CREATE only. Verify target/backup and existing definitions first; never backfill old receipts.
-- v91 is reserved by the parallel ticket-refund repair; this migration has no v91 dependency.
CREATE TABLE IF NOT EXISTS barrel_delivery_receipt (
 record_id BIGINT NOT NULL,
 input_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(record_id),
 CONSTRAINT chk_delivery_receipt_kind CHECK (input_kind IN ('ITEMIZED','SINGLE_PRODUCT_TOTAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐商品实收来源凭据；历史按比例分摊流水不回填';
CREATE TABLE IF NOT EXISTS barrel_return_delivery_allocation (
 return_record_id BIGINT NOT NULL,
 delivery_record_id BIGINT NOT NULL,
 quantity INT NOT NULL,
 operator_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(return_record_id),
 KEY idx_return_delivery_source(delivery_record_id,return_record_id),
 CONSTRAINT chk_return_delivery_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原退还申请引用已入账配送实收；不再次改实物或原退款批次';
