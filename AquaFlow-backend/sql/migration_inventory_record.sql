-- =============================================================================
-- [AQ-029] 新增库存流水表 inventory_record
-- 背景：水票/押金/桶都有流水表，唯独库存没有。inventory.quantity 被"入库 / 下单扣减 /
--       取消回补 / 退款回补"混写在同一字段，出现盘亏无法定位。本表为库存建立勾稽对象。
-- 幂等：可重复执行（IF NOT EXISTS）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `inventory_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '水站ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `delta` int NOT NULL COMMENT '库存变动量：正=入库/回补，负=消耗/出库',
  `type` varchar(32) NOT NULL COMMENT 'INBOUND入库 / CONSUME下单扣减 / CANCEL_RESTORE取消回补 / REFUND_RESTORE退款回补 / ADJUST盘点调整',
  `ref_id` bigint DEFAULT NULL COMMENT '关联单据ID（订单ID等）',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人员工ID',
  `note` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inv_record_station_product` (`station_id`,`product_id`,`create_time`),
  KEY `idx_inv_record_ref` (`ref_id`),
  KEY `idx_inv_record_type` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水';

-- 回填历史：把当前库存量作为一笔期初流水（仅当该 (station,product) 尚无流水时），
-- 使"流水累计 = 当前库存"从上线时点成立。历史明细无法追溯，用 INIT 类型标注期初。
INSERT INTO `inventory_record` (station_id, product_id, delta, type, ref_id, operator_id, note)
SELECT i.station_id, i.product_id, i.quantity, 'INIT', NULL, NULL, '期初库存回填'
FROM `inventory` i
WHERE i.quantity IS NOT NULL AND i.quantity <> 0
  AND NOT EXISTS (
    SELECT 1 FROM `inventory_record` r
    WHERE r.station_id = i.station_id AND r.product_id = i.product_id
  );

SELECT 'AQ-029 inventory_record 迁移完成' AS result;
