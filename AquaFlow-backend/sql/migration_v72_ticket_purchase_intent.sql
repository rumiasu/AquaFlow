-- v72 / F-75：购票请求的永久内容摘要，防止同键改商品/数量/档位命中原款。
-- 纯增可空列；不回填历史、不改金额或资产。新库 schema.sql 已含本列。
-- 经备份和目标核实后，先执行本增量，再部署引用该列的后端。禁止重导全库基线。
-- 使用显式目标库导入；不得将执行本脚本等同于授权迁移真实业务库。
SET @aq_v72_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
    AND COLUMN_NAME = 'purchase_request_digest');
SET @aq_v72_sql = IF(@aq_v72_exists = 0,
  'ALTER TABLE payment_record ADD COLUMN purchase_request_digest varchar(64) DEFAULT NULL COMMENT ''购票原请求SHA256摘要，不含价格；存量为空，见v72''',
  'SELECT ''v72 column exists; skipped'' AS result');
PREPARE aq_v72_stmt FROM @aq_v72_sql;
EXECUTE aq_v72_stmt;
DEALLOCATE PREPARE aq_v72_stmt;
