-- [AQ-054] orders.batch_id 为遗留死列：全仓库 Java / Mapper / XML 0 引用，且无对应 batch 表。
-- 数据中该列恒为 NULL，无任何读取或写入。删除以清理 schema 噪音。
ALTER TABLE orders DROP COLUMN batch_id;
