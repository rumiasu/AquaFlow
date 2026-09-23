-- =============================================================================
-- v50 · 企业身份申请（客户申请 → 站长审核 → 转企业身份）
--
-- 产品裁定（2026-09-18 第四批）：「企业和普通用户分离…我不建议做成入口，
-- 我觉得在订水时，检测到大额订单，会弹出确认是否是企业，可申请企业身份这种。」
--
-- 落地形状（**不做独立入口**）：订水报价时若金额超过阈值且该客户还是个人身份，
-- 响应里带一句可申请企业身份的提示 → 客户提交申请（本表）→ 站长在客户列表里审核 →
-- 通过后把 `customer.customer_type` 置 2（企业）并把企业资料写进既有的 `company_info`。
--
-- ⚠️ 整个功能由 `app.enterprise.enabled` 控制（**默认关闭**，环境变量
-- `ENTERPRISE_IDENTITY_ENABLED`）：关掉时报价不再下发提示、四个端点一律拒绝 ——
-- 甲方不满意可以立刻关，且关掉不会影响已经是企业身份的历史客户。
--
-- 影响面：**只加一张表**（纯新增，不动任何存量表/列/金额）。
-- 审核通过时才会写 `customer.customer_type` 与 `company_info`（那两张表本来就有这两处语义）。
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行无副作用。
-- 回滚：`DROP TABLE customer_enterprise_apply;`（申请记录随之消失，但已通过审核的
--       `customer_type` / `company_info` 不受影响 —— 那些是审核结果，不是申请本身）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `customer_enterprise_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL COMMENT '申请人（客户）',
  `station_id` bigint NOT NULL COMMENT '向哪个站申请（站长审核该站的申请）',
  `company_name` varchar(200) NOT NULL COMMENT '企业名称（必填）',
  `contact_person` varchar(100) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(100) DEFAULT NULL COMMENT '联系电话',
  `tax_no` varchar(64) DEFAULT NULL COMMENT '税号/统一社会信用代码（选填，先攒数据不对接开票）',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `review_note` varchar(255) DEFAULT NULL COMMENT '站长审核备注（驳回原因等）',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人（站长 staff.id）',
  `apply_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `review_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ent_apply_station_status` (`station_id`,`status`),
  KEY `idx_ent_apply_customer` (`customer_id`,`station_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业身份申请（v50，受 app.enterprise.enabled 开关控制）';
