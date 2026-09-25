-- =============================================================================
-- V35: 站级配送计费配置（Phase 1 · P1-B）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql.exe -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v35_station_delivery_config.sql"
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17，规格见 docs/design/17）
--
--   起送量、配送范围、运费、楼层费这四件事此前在系统里**完全不存在**
--   （全仓检索 起送|运费|配送费|delivery_fee|distance|配送范围 在 src/main 零命中）。
--   而它们是水站每天的判断依据：2 桶起送、超过 3 公里不送、无电梯加 2 元上楼费。
--
--   本表只存**经营参数**，不存计算结果：算出来的钱落在 orders.delivery_fee /
--   orders.floor_fee 上（下单时快照）。理由与押金/售价快照一致 ——
--   站长改配置**不能**改到历史订单的金额（本仓已有"改价改到客户已付的钱"的事故先例）。
--
-- 语义要点
--   · 一行一个水站；**没有行 = 没配过**，代码用 StationDeliveryConfig.defaults() 兜底成
--     「全 0、不拦单、只提示」。所以**存量水站的下单行为一个字都不会变**。
--   · 门槛处理方式是三选一（WARN 仅提示 / REJECT 不接单 / FEE 加收费用），默认 WARN。
--     ⚠️ 默认绝不能是 REJECT —— 本仓欠桶硬拦（原 MAX_OWED_BUCKETS=5）就是按产品决定移除、
--     改成"只提醒不阻断"的（AGENTS.md §1）。
--   · has_elevator / 楼层未填时**不收**楼层费（拿不准就不收，宁可少收不可乱收）。
--   · 费用**绝不并入 water_amount 或 deposit_amount** —— 后者是可退押金，
--     退款路径按它释放押金余额，混入会导致取消订单多退钱。
--
-- 影响面
--   · 纯新增 1 张表，**不改任何既有表/列、不动任何存量数据**；
--   · 存量水站没有配置行 → 全部走 defaults() → 不收费、不拦单，前端零感知。
--
-- 幂等：可重复执行（表查 information_schema.TABLES）。
-- 回滚：DROP TABLE station_delivery_config;
--       （回滚只失去"起送量/范围/运费/楼层费"的配置，不影响任何金额、桶账、水票、押金。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库
SET @has_station := (SELECT COUNT(*) FROM information_schema.TABLES
                     WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station');
SET @s := IF(@has_station>0,
  "SELECT '开始执行 V35（站级配送计费配置）' AS note",
  "SELECT 'ABORT: 当前库没有 station 表，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1) 建表
SET @s := IF((SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_delivery_config')=0,
  "CREATE TABLE station_delivery_config (
     station_id bigint NOT NULL COMMENT '水站ID（一站一行）',
     min_order_buckets int NULL DEFAULT NULL COMMENT '起送桶数（与 min_order_amount 取或；都为空=不限）',
     min_order_amount decimal(10,2) NULL DEFAULT NULL COMMENT '起送金额（水费口径，不含押金与运费）',
     min_order_mode varchar(10) NOT NULL DEFAULT 'WARN' COMMENT '未达起送量: WARN仅提示 / REJECT不接单 / FEE加收费用; 见 constant/DeliveryLimitMode',
     min_order_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT 'FEE 模式下未达起送量的加收金额',
     delivery_radius_m int NULL DEFAULT NULL COMMENT '配送半径(米); NULL=不限范围',
     over_radius_mode varchar(10) NOT NULL DEFAULT 'WARN' COMMENT '超范围: WARN / REJECT / FEE',
     remote_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT 'FEE 模式下超范围的加收金额',
     base_delivery_fee decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '基础配送费(未达免运费门槛时收)',
     free_delivery_buckets int NULL DEFAULT NULL COMMENT '免运费桶数门槛(与金额门槛取或; 都为空=一直收基础配送费)',
     free_delivery_amount decimal(10,2) NULL DEFAULT NULL COMMENT '免运费金额门槛(水费口径)',
     floor_free_level int NOT NULL DEFAULT 1 COMMENT '免费楼层(此层及以下不收楼层费)',
     floor_fee_per_level decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每超一层加收金额; 0=本站不收楼层费',
     floor_fee_mode varchar(10) NOT NULL DEFAULT 'PER_ORDER' COMMENT '楼层费口径: PER_ORDER按单 / PER_BUCKET按桶; 见 constant/FloorFeeMode',
     create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
     update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     PRIMARY KEY (station_id),
     CONSTRAINT fk_sdc_station FOREIGN KEY (station_id) REFERENCES station (id)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站级配送计费配置(起送量/配送范围/运费/楼层费); 无行=未配置, 等同全0不拦单'",
  "SELECT 'skip: station_delivery_config 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 校验
SELECT 'V35 完成：站级配送计费配置已就绪' AS note;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='station_delivery_config'
 ORDER BY ORDINAL_POSITION;

SELECT '校验B：存量水站均无配置行（应为 0 行，即行为与升级前完全一致）' AS check_item;
SELECT (SELECT COUNT(*) FROM station_delivery_config) AS config_rows,
       (SELECT COUNT(*) FROM station) AS station_rows;

SELECT '校验C：存量金额未受影响' AS check_item;
SELECT (SELECT IFNULL(SUM(total_amount),0) FROM orders) AS orders_total_sum,
       (SELECT COUNT(*) FROM orders WHERE delivery_fee <> 0 OR floor_fee <> 0) AS orders_nonzero_fee;
