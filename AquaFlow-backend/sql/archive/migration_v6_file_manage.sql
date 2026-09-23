-- STATUS: SUPERSEDED BY schema.sql
-- 此文件中的所有操作已被 schema.sql 吸收，新环境无需执行。

-- 文件管理表
CREATE TABLE IF NOT EXISTS file_info (
  id INT AUTO_INCREMENT PRIMARY KEY,
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
  file_type VARCHAR(50) DEFAULT '' COMMENT '文件类型(image/video/document/other)',
  mime_type VARCHAR(100) DEFAULT '' COMMENT 'MIME类型',
  url VARCHAR(500) NOT NULL COMMENT 'OSS访问地址',
  object_name VARCHAR(500) NOT NULL COMMENT 'OSS对象名',
  category VARCHAR(50) DEFAULT 'general' COMMENT '分类(general/banner/product/other)',
  uploader_id INT DEFAULT NULL COMMENT '上传者ID',
  uploader_name VARCHAR(50) DEFAULT '' COMMENT '上传者名称',
  create_time DATETIME DEFAULT NOW(),
  update_time DATETIME DEFAULT NOW() ON UPDATE NOW(),
  INDEX idx_category (category),
  INDEX idx_file_type (file_type),
  INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件管理表';
