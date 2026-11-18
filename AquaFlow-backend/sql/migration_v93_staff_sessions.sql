-- 先安装列及索引，再切换全部认证实例。原有凭据保持 NULL。
ALTER TABLE user_token
    ADD COLUMN session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '员工会话标识，历史凭据为NULL',
    ADD UNIQUE KEY uk_user_token_session (session_id);
