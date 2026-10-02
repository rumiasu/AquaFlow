-- v73 仅增加技术请求编号封锁凭据，不撤销款项，不修改支付状态。
-- 先备份、核实目标和停写旧代码，再安装 v72/v73 后启用新包；本脚本未在实际库执行。
-- closed_time 永久保留，不得按时间清理，否则迟到原请求能重新建款。
CREATE TABLE IF NOT EXISTS ticket_purchase_fence (
    customer_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    closed_time DATETIME NULL,
    PRIMARY KEY (customer_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购票请求编号永久封锁凭据';
