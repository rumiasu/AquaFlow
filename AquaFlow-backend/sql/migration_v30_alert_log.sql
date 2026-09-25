-- =============================================================================
-- V30: 新增 alert_log（分级告警：系统故障→系统管理员；运营故障→站长）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v30_alert_log.sql"
--
-- -----------------------------------------------------------------------------
-- 为什么需要这张表
--   2026-09-16 产品口径：「异常产生后通知站长做，但是分系统故障通知我、运营故障通知站长，各司其职」。
--   在此之前所有"通知"都只是 `log.info`（NotificationServiceImpl 六个方法全是日志桩），
--   于是有两个真实后果：
--     · 桶异常产生后**站长根本不知道**——`recordReturn` 里那行推送调用甚至是被注释掉的；
--     · 系统级问题的痕迹只在日志里，出事后只能翻日志，无法回答"谁该处理、处理没有"。
--   告警必须**先分类再投递**，并且**先落库**：日志是"谁去翻才有"，落库才是"可追责"。
--
-- 语义（应用侧唯一写入口：service/impl/AlertServiceImpl）
--   · alert_type = SYSTEM    → 收件人是系统管理员（开发者）；station_id 必须为 NULL。
--     例：对账不平、补偿执行失败、未预期的 500 异常。
--   · alert_type = OPERATION → 收件人是该水站站长；station_id 必填、staff_id 记当时取到的站长。
--     例：桶异常待处置、补偿已执行、协商缺水。
--   · notify_status：LOGGED=只落库+日志（外部渠道未配置）/ PUSHED=已推送 / FAILED=推送失败。
--     外部渠道（系统告警 webhook、站长侧微信订阅消息）未接入时，绝不等于"告警不存在"。
--
-- ⚠️ 可见性红线：站长端只允许查 `alert_type='OPERATION' and station_id=本站`（ManagerAlertController）。
--    系统告警没有 HTTP 入口，运维直接查表：
--      select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;
--
-- 影响面
--   · 纯新增 1 张表，不改任何既有表/列/数据；
--   · 写入是旁路（REQUIRES_NEW 独立事务 + 异常吞掉），不会因告警失败影响业务。
--
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS）。表已存在则跳过，并打印校验结果。
-- 回滚：DROP TABLE alert_log;（只丢告警历史，不影响任何业务数据与金额）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库：先确认当前连的是业务库（至少得有 orders 表）
SET @has_orders := (SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='orders');
SET @s := IF(@has_orders>0,
  "SELECT '开始执行 V30（新增 alert_log 分级告警表）' AS note",
  "SELECT 'ABORT: 当前库没有 orders 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 建表（幂等）
CREATE TABLE IF NOT EXISTS `alert_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `alert_type` varchar(16) NOT NULL COMMENT '告警归属：SYSTEM=系统故障(收件人=系统管理员) / OPERATION=运营故障(收件人=该站站长)；方向由 constant/AlertType 决定',
  `level` varchar(8) NOT NULL DEFAULT 'WARN' COMMENT 'ERROR/WARN/INFO',
  `source` varchar(64) NOT NULL COMMENT '产生位置（类/环节），排查时用来定位',
  `station_id` bigint DEFAULT NULL COMMENT '运营告警的收件水站；系统告警恒为 NULL（也是"不给站长看"的判据）',
  `staff_id` bigint DEFAULT NULL COMMENT '运营告警的收件站长；当时没有站长则为 NULL（只落库不丢）',
  `title` varchar(200) NOT NULL COMMENT '一句话摘要（人看的标题）',
  `content` text COMMENT '详情',
  `related_type` varchar(32) DEFAULT NULL COMMENT '关联业务对象类型，如 ORDER_BARREL_EXCEPTION',
  `related_id` bigint DEFAULT NULL COMMENT '关联业务对象 id',
  `notify_status` varchar(16) NOT NULL DEFAULT 'LOGGED' COMMENT 'LOGGED=只落库+日志 / PUSHED=已推送外部渠道 / FAILED=推送失败',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alert_type_station_time` (`alert_type`,`station_id`,`create_time`),
  KEY `idx_alert_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分级告警：系统故障→系统管理员；运营故障→水站站长';

-- 2) 校验：表存在 + 三个关键列存在
SELECT 'V30 完成：alert_log 已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='alert_log'
   AND COLUMN_NAME IN ('alert_type','station_id','notify_status');
