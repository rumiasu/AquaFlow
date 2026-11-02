-- READ ONLY. Verify the actual target and its backup before any separate migration/deployment.
SELECT DATABASE() AS actual_target_database;
SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='dispatch_agreement' AND column_name='offer_version';
SELECT CASE
    WHEN NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='dispatch_agreement')
        THEN 'dispatch_agreement absent: verify v71 prerequisites and intended feature before continuing'
    WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='dispatch_agreement' AND column_name='offer_version' AND data_type='bigint'
        AND is_nullable='NO' AND column_default='1')
        THEN 'v88 column matches the backend startup guard; this does not confirm deployment or business acceptance'
    WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='dispatch_agreement' AND column_name='offer_version')
        THEN 'STOP: existing offer_version definition differs; do not overwrite or blindly rerun migrations'
    ELSE 'v88 column missing: back up and apply only the required migration before the matching backend'
END AS preflight_result;
