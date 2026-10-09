-- v78：退桶原提交快照/安排版本及变更审计，取消申请处理结果。只新增旁表。
-- 本任务仅准备DDL；核准目标并备份后由统一发布流程安装。共享schema由统一收尾者合并。
CREATE TABLE IF NOT EXISTS barrel_return_arrangement (
  record_id BIGINT NOT NULL PRIMARY KEY,
  initial_pickup_mode VARCHAR(16) NOT NULL,
  initial_companion_order_id BIGINT NULL,
  version INT NOT NULL DEFAULT 1,
  requires_confirmation TINYINT NOT NULL DEFAULT 0,
  confirmed_version INT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 安装时尚无安排变更；永久固定原提交，不在重跑时覆盖既有快照或版本。
INSERT INTO barrel_return_arrangement(record_id,initial_pickup_mode,initial_companion_order_id,confirmed_version)
SELECT d.record_id,d.pickup_mode,d.companion_order_id,
       CASE WHEN d.customer_confirmed_time IS NOT NULL THEN 1 ELSE NULL END
FROM barrel_return_detail d
WHERE NOT EXISTS (SELECT 1 FROM barrel_return_arrangement a WHERE a.record_id=d.record_id);

CREATE TABLE IF NOT EXISTS barrel_return_arrangement_change (
  record_id BIGINT NOT NULL,
  version INT NOT NULL,
  actor_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  operator_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_digest VARCHAR(64) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  before_snapshot JSON NOT NULL,
  after_snapshot JSON NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY(record_id,version),
  UNIQUE KEY uk_return_arrangement_action(actor_key,record_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_cancel_result (
  request_id BIGINT NOT NULL PRIMARY KEY,
  result_note VARCHAR(300) NOT NULL,
  automatic TINYINT NOT NULL DEFAULT 0,
  operator_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
