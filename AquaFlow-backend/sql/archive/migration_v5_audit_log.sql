-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- Phase 4: 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT DEFAULT NULL COMMENT '操作用户ID',
    username VARCHAR(50) DEFAULT NULL COMMENT '操作用户名',
    role VARCHAR(20) DEFAULT NULL COMMENT '操作角色',
    module VARCHAR(50) NOT NULL COMMENT '模块: order/batch/inventory/customer/payment',
    action VARCHAR(50) NOT NULL COMMENT '操作: create/update/delete/cancel',
    target VARCHAR(100) DEFAULT NULL COMMENT '操作对象描述',
    detail TEXT DEFAULT NULL COMMENT '操作详情JSON',
    ip VARCHAR(50) DEFAULT NULL COMMENT '操作IP',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_module (module),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';

-- batch 表增加 station_id 列（如果还没有的话）
-- ALTER TABLE batch ADD COLUMN station_id INT DEFAULT NULL COMMENT '所属水站ID' AFTER total_qty;
