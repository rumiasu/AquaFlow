-- =============================================================================
-- [AQ-015] 转单状态结构化：order_transfer 表
-- 背景：转单（退回/转让/重分配/站间指定外派退回）此前完全靠 orders.special_note
--       的文本标记承载，列表靠 LIKE 判定。并发写必然丢失更新，且语义散落、无法审计。
-- 本表把转单状态结构化为独立记录，special_note 仅保留展示文案。
-- 幂等：CREATE IF NOT EXISTS；回填仅处理尚无结构化记录的订单。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `order_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `kind` varchar(20) NOT NULL COMMENT 'STAFF 配送员转单 / DIRECTED 站间指定外派退回',
  `sub_kind` varchar(30) DEFAULT NULL COMMENT 'RETURN_STATION/TRANSFER/REDISPATCH/DIRECTED_RETURN',
  `from_staff_id` bigint DEFAULT NULL COMMENT '发起方配送员ID',
  `to_staff_id` bigint DEFAULT NULL COMMENT '目标配送员ID',
  `from_station_id` bigint DEFAULT NULL COMMENT '发起方水站ID',
  `to_station_id` bigint DEFAULT NULL COMMENT '目标水站ID',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/CANCELLED',
  `reason` varchar(255) DEFAULT NULL COMMENT '原因/备注',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人员工ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ot_order` (`order_id`),
  KEY `idx_ot_order_status` (`order_id`,`status`),
  KEY `idx_ot_pending` (`status`,`kind`),
  KEY `idx_ot_from_staff` (`from_staff_id`),
  KEY `idx_ot_to_staff` (`to_staff_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单转单记录';

-- -----------------------------------------------------------------------------
-- 历史回填：从 special_note 文本标记还原结构化记录。
-- 规则（按当前生效的标记解析）：
--   [指定退回待确认] 未决 → kind=DIRECTED, sub_kind=DIRECTED_RETURN, status=PENDING
--   [退回站长] 未决（无 -已同意/-已拒绝）→ kind=STAFF, sub_kind=RETURN_STATION, status=PENDING
--   [转让] 未决 → kind=STAFF, sub_kind=TRANSFER, status=PENDING
--   [重分配] 未决 → kind=STAFF, sub_kind=REDISPATCH, status=PENDING
--   已决（含 -已同意/-已拒绝）→ 对应 status=APPROVED/REJECTED
-- 仅回填尚无结构化记录的订单，保证幂等。
-- -----------------------------------------------------------------------------
INSERT INTO order_transfer (order_id, kind, sub_kind, from_staff_id, to_staff_id, from_station_id, to_station_id, status, reason, operator_id, create_time, update_time)
SELECT o.id,
       IF(o.special_note LIKE '%[指定退回待确认]%', 'DIRECTED', 'STAFF'),
       CASE
         WHEN o.special_note LIKE '%[指定退回待确认]%' THEN 'DIRECTED_RETURN'
         WHEN o.special_note LIKE '%[退回站长%' THEN 'RETURN_STATION'
         WHEN o.special_note LIKE '%[转让]%' OR o.special_note LIKE '%[转让-已%' THEN 'TRANSFER'
         WHEN o.special_note LIKE '%[重分配%' THEN 'REDISPATCH'
         ELSE NULL
       END,
       o.delivery_staff_id, NULL, o.delivery_station_id, o.station_id,
       CASE
         WHEN o.special_note LIKE '%[指定退回待确认]%' THEN 'PENDING'
         WHEN o.special_note LIKE '%[退回站长-已同意]%'
           OR o.special_note LIKE '%[转让-已同意]%'
           OR o.special_note LIKE '%[重分配-已同意]%' THEN 'APPROVED'
         WHEN o.special_note LIKE '%[退回站长-已拒绝]%'
           OR o.special_note LIKE '%[转让-已拒绝]%'
           OR o.special_note LIKE '%[重分配-已拒绝]%' THEN 'REJECTED'
         ELSE 'PENDING'
       END,
       '迁移回填（自 special_note 解析）', NULL, o.create_time, o.update_time
FROM orders o
WHERE (o.special_note LIKE '%[指定退回待确认]%'
       OR o.special_note LIKE '%[退回站长%'
       OR o.special_note LIKE '%[转让]%' OR o.special_note LIKE '%[转让-已%'
       OR o.special_note LIKE '%[重分配%')
  AND NOT EXISTS (SELECT 1 FROM order_transfer t WHERE t.order_id = o.id);

SELECT 'AQ-015 order_transfer 迁移完成' AS result;
SELECT id, order_id, kind, sub_kind, status FROM order_transfer ORDER BY id;
