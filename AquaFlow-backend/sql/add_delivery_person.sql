-- STATUS: SUPERSEDED BY schema.sql
-- batch.delivery_person_id 列已在 schema.sql 中定义，新环境无需执行。

-- 为 batch 表添加配送员字段
ALTER TABLE batch ADD COLUMN delivery_person_id INT NULL COMMENT '配送员ID，关联staff表' AFTER station_id;

-- 添加索引以优化查询
CREATE INDEX idx_batch_delivery_person ON batch(delivery_person_id);
