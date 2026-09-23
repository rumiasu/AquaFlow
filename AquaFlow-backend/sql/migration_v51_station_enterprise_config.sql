-- =============================================================================
-- V51: 站级「企业身份提示阈值」配置（一站一行）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v51_station_enterprise_config.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-19，第五批裁定；规格见 docs/design/16 D24）
--   产品原话：「企业的只看水，押金不算，水超过 30 桶就可以吧。也可以由水站设置」，
--   并就"桶数还是金额"追了一句：「桶数或金额，站长也可以自行设置范围，可以任选其一也可都选」。
--   于是触发口径从 v50 的「订单总额 ≥ 阈值」改成两条**只算水**的口径，且阈值可按站配：
--     · 桶数口径：本单桶装水（product.category=1）数量合计 ≥ barrel_threshold
--     · 金额口径：本单**水费**（orders 口径的 water_amount，不含押金/配送费/楼层费）≥ water_amount_threshold
--     · 两项都配 = **任一满足**即提示；只配一项 = 只按那一项；两项都空 = 该站不提示。
--   ⚠️ 「没有行」与「有一行但两项都为空」语义不同，别合并：
--     · **没有行 = 还没配过** → 用平台默认（app.enterprise.large-order-barrels，默认 30 桶）；
--     · **有行且两项空 = 这位站长明确表示本站不提示** → 一个人都不提示。
--     与 station_delivery_config 的"无行=全0"同形，但这里多了一层"显式关掉"，因为
--     "平台默认 30 桶"是新站的兜底，而老站可能就想安静。
--
-- 影响面
--   · 纯新增 1 张表：**不改任何既有表/列、不动任何存量数据**；
--   · 存量水站没有配置行 → 全部走平台默认（30 桶），与本次升级前的"金额 500 元"相比，
--     触发面**变小且更贴合桶装水业务**（1~5 桶的散客单不再被问"你是不是企业"）；
--   · 阈值只是"要不要弹一次提示"，**不拦单、不影响金额**。
--
-- 幂等：可重复执行（查 information_schema.TABLES）。
-- 回滚：DROP TABLE station_enterprise_config;
--       （回滚只会丢掉站级阈值，全部回到平台默认；不影响任何订单、桶账、水票、押金。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_station := (SELECT COUNT(*) FROM information_schema.TABLES
                     WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station');
SET @s := IF(@has_station>0,
  "SELECT '开始执行 V51（站级企业身份提示阈值）' AS note",
  "SELECT 'ABORT: 当前库没有 station 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 建表
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_enterprise_config')=0,
  "CREATE TABLE `station_enterprise_config` (
     `station_id` bigint NOT NULL COMMENT '水站ID（一站一行）',
     `barrel_threshold` int DEFAULT NULL COMMENT '本单桶装水(product.category=1)达到该桶数即提示可申请企业身份；NULL=该项不启用',
     `water_amount_threshold` decimal(10,2) DEFAULT NULL COMMENT '本单水费达到该金额即提示（不含押金/配送费/楼层费）；NULL=该项不启用',
     `operator_id` bigint DEFAULT NULL COMMENT '最后修改人（站长 staff.id）',
     `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (`station_id`),
     CONSTRAINT `fk_sec_station` FOREIGN KEY (`station_id`) REFERENCES `station` (`id`)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站级企业身份提示阈值(v51); 无行=用平台默认, 有行且两项空=本站不提示'",
  "SELECT 'skip: station_enterprise_config 已存在' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 自检
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_enterprise_config') AS table_ready,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_enterprise_config') AS column_count,
  (SELECT COUNT(*) FROM station_enterprise_config) AS configured_stations;
