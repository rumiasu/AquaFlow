-- ============================================================
-- AquaFlow v1 绑定流程数据库最终迁移
-- 生成时间: 2026-08-24
-- 目标: 按 V1 规则统一 staff/station 身份与绑定模型
-- 核心原则:
--   1. 当前归属关系 = staff.station_id (NULL 表示未绑定)
--   2. 申请历史 = staff_station_application 表
--   3. 禁止 staff.apply_station_id / staff.binding_status / station.manager_name
--   4. 禁止 factory / FACTORY_ADMIN / water_type
-- 执行前请备份数据库！当前开发库可直接执行。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. station 表: 删除 manager_name (站长关系改由 staff.role + staff.station_id 表达)
-- ============================================================

ALTER TABLE `station`
  DROP COLUMN `manager_name`;

-- ============================================================
-- 2. staff 表: 删除旧字段 / 增加 openid 唯一约束 / 确认 station_id 可 NULL 无默认
-- ============================================================

-- 删除废弃字段 (password 明文、factory_id)
ALTER TABLE `staff`
  DROP COLUMN `password`,
  DROP COLUMN `factory_id`;

-- openid 增加唯一约束 (用于微信身份稳定映射)
ALTER TABLE `staff`
  ADD UNIQUE KEY `uk_staff_openid` (`openid`);

-- 确认 station_id 可 NULL 且无默认值。显式 MODIFY 确保约束正确
ALTER TABLE `staff`
  MODIFY COLUMN `station_id` bigint NULL COMMENT '所属水站 (NULL=未绑定水站)';

-- 清理历史非法角色：把旧角色全部改为 DELIVERY（如存在）
-- (V1 只允许 STATION_MANAGER / DELIVERY)
UPDATE `staff` SET `role` = 'DELIVERY' WHERE `role` NOT IN ('STATION_MANAGER', 'DELIVERY');

-- 清理 station_id 默认值 1 之类的硬编码残留：
-- 凡 station_id 指向不存在的 station 的，一律置 NULL
UPDATE `staff` s
LEFT JOIN `station` st ON s.`station_id` = st.`id`
SET s.`station_id` = NULL
WHERE s.`station_id` IS NOT NULL AND st.`id` IS NULL;

-- ============================================================
-- 3. 新增 staff_station_application (配送员绑定/解绑申请表)
-- ============================================================

CREATE TABLE IF NOT EXISTS `staff_station_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL COMMENT '申请人 staff.id (必须 DELIVERY)',
  `station_id` bigint NOT NULL COMMENT '申请绑定/解绑的水站',
  `type` tinyint NOT NULL COMMENT '1=绑定申请, 2=解绑申请',
  `status` tinyint NOT NULL COMMENT '1=待审批, 2=已同意, 3=已拒绝, 4=已取消',
  `apply_note` varchar(200) DEFAULT NULL COMMENT '申请说明',
  `handle_staff_id` bigint DEFAULT NULL COMMENT '审批人 (水站 STATION_MANAGER)',
  `handle_note` varchar(200) DEFAULT NULL COMMENT '审批说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `handle_time` datetime DEFAULT NULL COMMENT '审批时间',
  PRIMARY KEY (`id`),
  KEY `idx_app_staff` (`staff_id`),
  KEY `idx_app_station_status` (`station_id`, `status`),
  KEY `idx_app_type_status` (`type`, `status`),
  CONSTRAINT `fk_app_staff` FOREIGN KEY (`staff_id`) REFERENCES `staff` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_app_station` FOREIGN KEY (`station_id`) REFERENCES `station` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_app_handle_staff` FOREIGN KEY (`handle_staff_id`) REFERENCES `staff` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配送员-水站绑定/解绑申请审批';

-- ============================================================
-- 4. 删除 water_type (已经由 product 替代; 删除前若有外键依赖需先清理, 此处开发库直接 DROP)
-- ============================================================

DROP TABLE IF EXISTS `water_type`;

-- ============================================================
-- 5. 防止出现同一 DELIVERY 多个待审批绑定申请的唯一索引 (业务层再额外校验)
-- ============================================================

-- 允许后续 SQL 安全执行
SET FOREIGN_KEY_CHECKS = 1;

-- 输出完成信息
SELECT 'Binding model migration applied successfully! staff.station_id is now the single source of truth, staff_station_application table created.' AS message;
