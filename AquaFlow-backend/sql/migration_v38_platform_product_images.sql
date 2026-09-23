-- =============================================================================
-- V38: 通用商品库配图（平台预设图 · 不依赖 COS）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   export MYSQL_PWD=<密码>; D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v38_platform_product_images.sql"
--   ⚠️ 不要用 `cmd /c "mysql ... < file.sql"` —— 本仓的 Bash 沙箱会以
--      "Invoking cmd.exe from Bash bypasses all command validation" 拦截。
--   ⚠️ 也不要用 PowerShell 管道（`Get-Content | mysql`）—— 会逐行处理、破坏多字节字符与引号。
-- 执行前先备份：mysqldump 到 backup/。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-17）
--
--   顾客端与站长端此前**一张商品图都看不到**：通用商品库（product.owner_station_id IS NULL）
--   仅有 2 条种子商品，两条的 image_object_name 全是 NULL → 前端一律回退到 💧 文本占位。
--
--   而"补图"卡在一个现实约束上：**本机 COS 未配置**（application-local.yml 的 cos 段整段被注释，
--   RequiredConfigChecker 只 WARN 不拒启）。此时 CosUtil.generatePublicUrl 抛异常被
--   withImageUrls 的 catch 吞掉 → 接口照常 200 但 imageUrl 恒为 null → **不报错、静默无图**。
--   也就是说，只要图走 COS 对象键，本地就永远看不到效果，且极易被误判成前端缺陷。
--
--   故本版采用**平台预设图 + 小程序包内本地资源**的口径（docs/design/14 §3.3 方案 A）：
--     image_object_name 存**本地资源路径**（形如 /assets/product/barrel-water.webp），
--     由 util/ProductImageResolver#resolve 原样下发（判据：以 / 开头即视为本地资源，不经 COS）。
--   好处：零外部依赖、不过期（COS 预签名 URL 只有 24h）、无需网络；
--   且**将来接入 COS 只需换列值、不用改代码** —— 因为解析器同时支持两种口径。
--
-- -----------------------------------------------------------------------------
-- 语义
--   · 纯数据迁移，**无 DDL**：product.image_object_name 列早已存在（varchar(500)，'图片 COS 对象键'）。
--   · 只 UPDATE 通用库（owner_station_id IS NULL）商品的 image_object_name，
--     且**只在该列为 NULL 时才写**（不覆盖任何人已配过的值）—— 这一条件使脚本天然幂等。
--   · **绝不 UPDATE name/brand/spec**：preset_uk 是 (name|brand|spec) 的 STORED 生成列 +
--     唯一键 uk_product_preset，改这三个字段会撞唯一键；而 image_object_name
--     不参与任何生成列，改动绝对安全。
--   · **绝不动 owner_station_id**：把种子商品改成某站私有会破坏"通用库"语义。
--
-- 影响面
--   · 只改 2 行的 1 个文本列；不新增/删除表、列、索引；不动金额、不动库存、不动订单与资产。
--   · 对业务零影响：image_object_name 目前仅被图片解析链路读取，不参与计价、库存、对账任何计算。
--
-- 幂等
--   · 有条件 UPDATE（WHERE image_object_name IS NULL）→ 二次执行影响 0 行。可安全重跑。
--
-- 回滚
--   · 置空即可恢复原状（本就全为 NULL）：
--       UPDATE product SET image_object_name = NULL WHERE owner_station_id IS NULL;
--   · 回滚只丢商品图路径，不影响任何金额、库存、订单与资产数据。
--
-- ⚠️ 前端配套（本迁移**依赖**它，漏了就是"跑了迁移也没图"）
--   两个小程序的包内都必须存在对应文件，且路径**严格一致**：
--     miniapp-user/assets/product/barrel-water.webp      （通用桶装水）
--     miniapp-delivery/assets/product/barrel-water.webp  （同上，两个包各存一份 —— 微信包内资源不能跨小程序共享）
--   映射的唯一真值在 constant/ProductImageKeys（key → 路径），新增预设图必须同步改那里 + 两个包。
--
-- -----------------------------------------------------------------------------
-- 执行记录（2026-09-17）
--   · 目标库：aquaflow（真实库）
--   · 备份：backup/aquaflow_before_v38_20260917-224458.sql（96K）
--   · 结果：第 3 步影响 **2 行**（id=1,2）；二次执行影响 **0 行**（幂等已验证）
--   · 校验：A 两条均为 `/assets/product/barrel-water.webp`（本地预设图 OK）；
--           B 站内自定义商品未被波及；C product 总行数 2 未变；D preset_uk 保持原值
--   · 备注：本脚本**只挂图、不改 name/brand/spec**。原计划"改名成通用名"经核实**已放弃** ——
--           product_id 被 15 张表引用（含 order_item/deposit_record/ticket_record/customer_barrel_*），
--           且 inventory 已有 2 条真实选用记录，改名会篡改历史订单与资产记录的留痕。
--   · 2026-09-18 二次执行（接手后补）：第 3 步影响 **0 行**（幂等再次确认）；
--           第 5 步把 `product.image_object_name` 的列注释从「图片 COS 对象键」补成
--           「预设图路径 或 COS 对象键」两种口径（与 schema.sql 逐字一致）——
--           只改注释，**不动列类型、不动任何数据**。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检（不满足直接终止，绝不半途改数据）
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('product','inventory','orders'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（product/inventory/orders 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止，未做任何修改')) AS precheck;

-- 若预检不通过则抛错中断（MySQL 无 RAISE，用"查询不存在的表"制造显式失败）
SET @abort := IF(@tbl_cnt <> 3,
                 'SELECT * FROM __ABORT_WRONG_DATABASE__',
                 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：确认列存在（列早已存在，这里只做断言，不改 DDL）
-- -----------------------------------------------------------------------------
SET @col_cnt := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'product'
                   AND COLUMN_NAME = 'image_object_name');
SELECT IF(@col_cnt = 1,
          'OK: product.image_object_name 已存在（无需 DDL）',
          'ABORT: 缺少 image_object_name 列，请先确认库版本（预期由 v31 之前的基线提供）') AS column_check;

-- -----------------------------------------------------------------------------
-- 第 2 步：查看执行前的状态（留痕）
-- -----------------------------------------------------------------------------
SELECT '--- 执行前：通用库商品 ---' AS step;
SELECT id, name, brand, spec, image_object_name
FROM product
WHERE owner_station_id IS NULL
ORDER BY id;

-- -----------------------------------------------------------------------------
-- 第 3 步：配图（仅 NULL 时写入 → 幂等）
--   两条种子商品都是"桶装水"（category=1），业务上是同一种循环桶商品，
--   故共用同一张无品牌通用桶图（决策：不做水种细分图，避免图文不符）。
-- -----------------------------------------------------------------------------
UPDATE product
SET image_object_name = '/assets/product/barrel-water.webp',
    update_time       = NOW()
WHERE owner_station_id IS NULL
  AND image_object_name IS NULL
  AND category = 1;

SELECT CONCAT('第 3 步完成：影响 ', ROW_COUNT(), ' 行') AS note;

-- -----------------------------------------------------------------------------
-- 第 4 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：通用库商品配图结果 ---' AS step;
SELECT id, owner_station_id, name, category, image_object_name,
       CASE WHEN image_object_name IS NULL THEN '未配图'
            WHEN image_object_name LIKE '/%' THEN '本地预设图 OK'
            ELSE '⚠️ 非本地路径（会走 COS，本地将显示不出）' END AS image_check
FROM product
WHERE owner_station_id IS NULL
ORDER BY id;

SELECT '--- 校验 B：站内自定义商品未被波及（应全部保持原值）---' AS step;
SELECT id, owner_station_id, name, image_object_name
FROM product
WHERE owner_station_id IS NOT NULL
ORDER BY id;

SELECT '--- 校验 C：产品总行数（应与执行前一致）---' AS step;
SELECT COUNT(*) AS product_total FROM product;

SELECT '--- 校验 D：唯一键相关字段未被改动（preset_uk 应保持原值）---' AS step;
SELECT id, preset_uk, station_uk FROM product ORDER BY id;

-- -----------------------------------------------------------------------------
-- 第 5 步：同步列注释（2026-09-18 补）
--   本步之前该列注释只写「图片 COS 对象键」，而 v38 起它同时承载"包内预设图路径"——
--   注释与取值不符比没有注释更危险（下一个人会照着"这里只能是对象键"去改解析器）。
--   与 schema.sql 的同名列注释**逐字一致**，改一处必须改两处。
-- -----------------------------------------------------------------------------
SET @cur_comment := (SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'product'
                       AND COLUMN_NAME = 'image_object_name');
SET @want_comment := '图片: 小程序包内预设图路径(/assets/product/xxx.webp) 或 COS 对象键; 判据=以/开头即本地资源(原样下发), 否则走 COS 签名(v38 起)';
SET @ddl := IF(@cur_comment <=> @want_comment,
               'SELECT ''skip: product.image_object_name 注释已是最新'' AS note',
               'ALTER TABLE product MODIFY COLUMN image_object_name varchar(500) DEFAULT NULL COMMENT ''图片: 小程序包内预设图路径(/assets/product/xxx.webp) 或 COS 对象键; 判据=以/开头即本地资源(原样下发), 否则走 COS 签名(v38 起)''');
PREPARE st_comment FROM @ddl;
EXECUTE st_comment;
DEALLOCATE PREPARE st_comment;

SELECT 'V38 完成：通用库商品已配平台预设图（本地资源路径，不依赖 COS）+ 列注释已同步' AS note;
