-- v88: bind acceptance to a particular OFFERED round; no historical financial amounts are changed.
-- Back up and inspect the target schema first. Run explicitly before deploying the matching backend.
-- Idempotent for the expected schema; the backend guard still rejects an incompatible existing column.
SET @offer_version_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'dispatch_agreement' AND column_name = 'offer_version');
SET @offer_version_ddl = IF(@offer_version_exists = 0,
    'ALTER TABLE dispatch_agreement ADD COLUMN offer_version BIGINT NOT NULL DEFAULT 1 COMMENT ''外派报价轮次版本''',
    'SELECT ''v88 offer_version already exists; verify BIGINT NOT NULL DEFAULT 1'' AS migration_status');
PREPARE offer_version_stmt FROM @offer_version_ddl;
EXECUTE offer_version_stmt;
DEALLOCATE PREPARE offer_version_stmt;
