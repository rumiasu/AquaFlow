-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- 反馈建议表
CREATE TABLE IF NOT EXISTS feedback (
    id INT AUTO_INCREMENT PRIMARY KEY,
    staff_id INT DEFAULT NULL COMMENT '提交人配送员ID',
    category VARCHAR(50) DEFAULT NULL COMMENT '分类：bug/feature/other',
    content TEXT NOT NULL COMMENT '反馈内容',
    contact VARCHAR(100) DEFAULT NULL COMMENT '联系方式',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反馈建议';
