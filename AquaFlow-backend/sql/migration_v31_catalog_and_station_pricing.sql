-- =============================================================================
-- V31: 通用商品库 + 本站定价（商品与库存重构的数据库底座）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE；**导入必须走字节级重定向**，不要用 PowerShell 管道）：
--   cmd /c "D:\backend\MySQL\bin\mysql -uroot -p<密码> --default-character-set=utf8mb4 aquaflow < migration_v31_catalog_and_station_pricing.sql"
-- 执行前先备份：mysqldump 到 backup/（见 AGENTS.md §1/§4 的既有做法）。
--
-- -----------------------------------------------------------------------------
-- 为什么需要这次改造（2026-09-16 产品决定）
--   现状：`product` 是**全局表且无归属列**，站长能在「商品与库存」页自由新增商品，
--   于是站 A 建的商品会出现在站 B 的管理列表里（`ProductMapper.listWithInventory` 对
--   product 不设任何过滤），站长之间既互相可见、也能互相改（`ManagerProductController`
--   的 update/delete 只判存在性）。产品口径改为：
--     · **通用商品库**（本表 owner_station_id IS NULL）= 由开发者/运维维护的"选品目录"，
--       站长**只读**，只能"选用"到自己站；
--     · **本站设置**（`inventory` 一行 = 本站对某商品的上架/库存/售价/押金/水票/优先展示）；
--     · **自定义商品**（owner_station_id = 本站）= 入不了通用库的临时品，**仅本站可见**，
--       站长可完整编辑（名称/规格/图片也只能在自定义商品上改）。
--   ⚠️ 不做平台级功能：没有"平台停售影响所有站"这类语义，站长之间严格隔离、无权干预别站。
--
-- 语义（应用侧唯一口径见 docs/design/12-商品与库存重构.md）
--   · product.owner_station_id  NULL = 通用库；非 NULL = 该站自定义（不入通用库）
--   · inventory.sale_price      本站售价；NULL = 回落 product.price（通用库参考价）
--   · inventory.deposit_price   本站押金；NULL = 回落 product.deposit
--   · inventory.ticket_price    本站水票价（**早已存在**，本次不动；>0 才生效，与 PriceUtil 一致）
--   · product_submission        站长把自定义商品"上报给开发者补充进通用库"的登记表
--
-- 影响面
--   · 只加列/加索引/建 1 张新表，**不改任何既有列的类型与含义、不动任何存量数据**；
--   · `product.id` 是 15 张业务表（order_item/orders/barrel_record/customer_barrel_*/
--     ticket_*/inventory_record/station_adjustment/order_barrel_exception/...）的锚点，
--     因此**绝不拆表**：通用库与自定义商品共用 product 表，靠 owner_station_id 逻辑隔离。
--     物理拆成两张商品表会让上述 15 处 product_id 与 7 个含 product_id 的唯一键语义分叉。
--   · 两个生成列 + 唯一键只是为了防"重复录入"（运维友好）：MySQL 唯一键中 NULL 互不冲突，
--     所以必须用 STORED 生成列表达"条件唯一"（先例：payment_record.active_order_id / v28）。
--
-- 幂等：可重复执行（列查 information_schema.COLUMNS、索引查 information_schema.STATISTICS、
--       表用 CREATE TABLE IF NOT EXISTS）。
-- 回滚：ALTER TABLE product DROP INDEX uk_product_station, DROP INDEX uk_product_preset,
--         DROP COLUMN station_uk, DROP COLUMN preset_uk, DROP COLUMN owner_station_id;
--       ALTER TABLE inventory DROP COLUMN sale_price, DROP COLUMN deposit_price;
--       DROP TABLE product_submission;
--       （回滚只丢"本站定价/归属/上报登记"，不影响订单、桶账、水票的任何金额。）
-- =============================================================================

SET @db := DATABASE();

-- 0) 防误库：三张关键表都得在
SET @has_tbls := (SELECT COUNT(*) FROM information_schema.TABLES
                  WHERE TABLE_SCHEMA=@db AND TABLE_NAME IN ('orders','product','inventory'));
SET @s := IF(@has_tbls=3,
  "SELECT '开始执行 V31（通用商品库 + 本站定价）' AS note",
  "SELECT 'ABORT: 当前库缺少 orders/product/inventory 之一，请确认连的是 aquaflow 库' AS note");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 1) product.owner_station_id：归属列（NULL=通用库）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND COLUMN_NAME='owner_station_id')=0,
  "ALTER TABLE product ADD COLUMN owner_station_id bigint NULL DEFAULT NULL COMMENT '归属水站: NULL=通用商品库(开发者维护, 站长只读); 非NULL=该站自定义商品(仅本站可见, 可完整编辑)' AFTER id",
  "SELECT 'skip: product.owner_station_id 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND INDEX_NAME='idx_product_owner_station')=0,
  "ALTER TABLE product ADD KEY idx_product_owner_station (owner_station_id, status)",
  "SELECT 'skip: idx_product_owner_station 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 2) 通用库防重复录入：preset_uk = 名称|规格（仅通用库行有值）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND COLUMN_NAME='preset_uk')=0,
  "ALTER TABLE product ADD COLUMN preset_uk varchar(220) GENERATED ALWAYS AS (IF(owner_station_id IS NULL, CONCAT(name,'|',IFNULL(brand,''),'|',IFNULL(spec,'')), NULL)) STORED COMMENT '通用库去重键(生成列): 通用库行=名称|品牌|规格, 自定义商品行=NULL。唯一键中 NULL 互不冲突, 故用生成列表达条件唯一'",
  "SELECT 'skip: product.preset_uk 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND INDEX_NAME='uk_product_preset')=0,
  "ALTER TABLE product ADD UNIQUE KEY uk_product_preset (preset_uk)",
  "SELECT 'skip: uk_product_preset 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 3) 站内自定义商品防重复：station_uk = 站号:名称|规格（仅自定义行有值）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND COLUMN_NAME='station_uk')=0,
  "ALTER TABLE product ADD COLUMN station_uk varchar(240) GENERATED ALWAYS AS (IF(owner_station_id IS NULL, NULL, CONCAT(owner_station_id,':',name,'|',IFNULL(brand,''),'|',IFNULL(spec,'')))) STORED COMMENT '站内自定义商品去重键(生成列): 同一站不允许同名同品牌同规格两条; 通用库行=NULL'",
  "SELECT 'skip: product.station_uk 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
                AND INDEX_NAME='uk_product_station')=0,
  "ALTER TABLE product ADD UNIQUE KEY uk_product_station (station_uk)",
  "SELECT 'skip: uk_product_station 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 4) inventory.sale_price：本站售价（NULL = 回落 product.price）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory'
                AND COLUMN_NAME='sale_price')=0,
  "ALTER TABLE inventory ADD COLUMN sale_price decimal(10,2) NULL DEFAULT NULL COMMENT '本站售价; NULL=回落 product.price(通用库参考价)。计价唯一入口 util/PriceUtil#calcUnitPrice' AFTER enabled",
  "SELECT 'skip: inventory.sale_price 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 5) inventory.deposit_price：本站押金（NULL = 回落 product.deposit）
-- -----------------------------------------------------------------------------
SET @s := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory'
                AND COLUMN_NAME='deposit_price')=0,
  "ALTER TABLE inventory ADD COLUMN deposit_price decimal(10,2) NULL DEFAULT NULL COMMENT '本站押金(仅桶装水使用); NULL=回落 product.deposit。下单时必须快照进 customer_barrel_in_transit.unit_price' AFTER sale_price",
  "SELECT 'skip: inventory.deposit_price 已存在' AS r");
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 6) product_submission：自定义商品"上报给开发者补进通用库"的登记表
--    产品口径：自定义商品不入通用库；站长可以上报，开发者人工补录后再把该商品
--    转为通用库行（owner_station_id 置 NULL）或保留为站内私有。
--    ⚠️ 不设唯一键：被驳回后允许再次上报；同一商品"待处理只能有一条"由 service 保证。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` bigint NOT NULL COMMENT '上报水站',
  `product_id` bigint NOT NULL COMMENT '被上报的商品（product.id，必为本站自定义商品）',
  `submitter_staff_id` bigint DEFAULT NULL COMMENT '上报人（站长）；取不到则 NULL，只影响追溯',
  `note` varchar(200) DEFAULT NULL COMMENT '站长补充说明（规格/品牌/进货渠道等）',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 已纳入通用库 / 2 已驳回',
  `handle_note` varchar(200) DEFAULT NULL COMMENT '开发者处置说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_submission_status_time` (`status`,`create_time`),
  KEY `idx_submission_product` (`product_id`),
  KEY `idx_submission_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站长自定义商品上报通用库登记表（平台侧人工处理，站长端只写只读自己的）';

-- -----------------------------------------------------------------------------
-- 7) 存量数据口径（**不动数据**，只说明 + 打印现状供确认）
--    现有 product 行没有 creator 字段，无法自动判断"当初是哪个站长建的"。
--    本脚本**保持 owner_station_id = NULL**，即把存量商品一律视为「通用库」——
--    对当前真实库是合适的（2 行都是 2026-09-15 的测试种子数据）。
--    若某个存量行其实应归某站私有，手工执行（示例，站号按需替换）：
--      UPDATE product SET owner_station_id = 1 WHERE id IN (1,2);
--      -- ⚠️ 执行前确认该商品未被其它站的 inventory 引用，否则那些站会突然"看不见"它。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 8) 校验
-- -----------------------------------------------------------------------------
SELECT '校验A：product 三个新列（owner_station_id 可空 / 两个生成列）' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, EXTRA
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
   AND COLUMN_NAME IN ('owner_station_id','preset_uk','station_uk')
 ORDER BY ORDINAL_POSITION;

SELECT '校验B：inventory 两个新列' AS check_item;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='inventory'
   AND COLUMN_NAME IN ('sale_price','deposit_price')
 ORDER BY ORDINAL_POSITION;

SELECT '校验C：两个唯一键 + 归属索引' AS check_item;
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product'
   AND INDEX_NAME IN ('uk_product_preset','uk_product_station','idx_product_owner_station')
 GROUP BY INDEX_NAME, NON_UNIQUE;

SELECT '校验D：product_submission 已就绪' AS check_item;
SELECT COUNT(*) AS product_submission_exists
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA=@db AND TABLE_NAME='product_submission';

SELECT '校验E：存量商品归属现状（应全部为 NULL=通用库）' AS check_item;
SELECT id, name, spec, owner_station_id, preset_uk, station_uk FROM product ORDER BY id;

SELECT '校验F：行数未受影响' AS check_item;
SELECT (SELECT COUNT(*) FROM product) AS product_rows,
       (SELECT COUNT(*) FROM inventory) AS inventory_rows,
       (SELECT COUNT(*) FROM orders) AS orders_rows;
