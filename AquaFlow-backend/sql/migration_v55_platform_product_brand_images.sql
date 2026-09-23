-- =============================================================================
-- V55: 平台通用库商品图 —— 把「有官网实拍图且能精确对应」的品牌行换掉统一通用图
-- =============================================================================
--
-- 【背景】用户 2026-09-19 指令：「你之前把图都统一修了吗，改回来吧，每个都先用官网图，
--   后期我再改」
--
--   事实核对（已查证）：确实是我把通用库 52 行的图**统一成了同一张** ——
--     · v38  给当时仅有的 2 行挂上 /assets/product/barrel-water.webp
--     · v52 / v53 新增的 50 行，INSERT 里**逐行写死了同一张** barrel-water.webp
--     ⇒ 通用库 52 行图片完全一致，站长选品时无法凭图区分品牌。
--
-- 【本迁移做什么】
--   只覆盖**既有官网实拍图、又能与商品精确一一对应**的两条品牌线，共 5 行：
--     普利思（源 www.pulisi.com）     纯净水 / 天然泉水 / 天然矿泉水
--     崂山  （源 www.laoshan.com.cn） 矿泉水 / 山泉水
--   匹配键用 `brand + name LIKE`，不依赖自增 id —— 换库、重灌种子后依然成立。
--
-- 【本迁移刻意不做什么】—— 剩余 47 行保持 barrel-water.webp 不动
--   素材库（assets/product-preset-sources/）只采集了 5 个品牌，且能用的只有上面两条线：
--     · 娃哈哈     —— 采集到的是「多规格组合图 + 纯黑底」，不是单品桶装水，不可用
--     · 农夫山泉   —— 只有 4L 一次性桶图，无 19L 循环桶图（库里那行是 19L 循环桶）
--     · 景田百岁山 —— 只有瓶装与 4.5L 一次性桶，无 18.9L 循环桶图
--   其余 29 个品牌（云恬 / 一山一水 / 山下泉 / 天地矿泉 / 圣境甘泉 / 惬尔 / 艺韵 /
--   畅饮吧 / 好山好水 / 百圣泉 / 涵露 / 七星台 / 泉城茗水 / 爱茶说 / 泰山甘泉 / 雪峪 /
--   象牙山 / 趵突泉 / 冰露 / 泉娃 / 百脉泉 / 怡宝 / 雀巢 / 恒大 / 泉阳泉 / 乐百氏 /
--   润田翠 / 阿尔卑斯 …）**尚无素材**；其中济南本地小品牌多数**根本没有官网**
--   （已实测：云恬 / 一山一水 / 天地矿泉 只有招商软文与黄页，无官网；百脉泉
--   sdbaimaiquan.com、泉娃 quanwa.com 有官网，产品图待采）。
--   ⇒ 「每行都挂自己品牌的官网图」需要另行采集，**不在本迁移内**。
--
-- 【不做 DDL】
--   product.image_object_name 列早已存在，本脚本是**纯数据迁移**。
--
-- 【幂等】
--   每条 UPDATE 都带「目标值不等于现值」条件 + 计入 ROW_COUNT
--   → 二次执行影响 0 行，可安全重跑。
--
-- 【回滚】（恢复统一通用图）
--   UPDATE product SET image_object_name = '/assets/product/barrel-water.webp', update_time = NOW()
--   WHERE owner_station_id IS NULL AND image_object_name IN (
--     '/assets/product/pulisi-pure.webp',   '/assets/product/pulisi-spring.webp',
--     '/assets/product/pulisi-mineral.webp','/assets/product/laoshan-mineral.webp',
--     '/assets/product/laoshan-spring.webp');
--
-- -----------------------------------------------------------------------------
-- ⚠️ 前端配套（本迁移**依赖**它，漏了就是「跑了迁移也没图」）
--   两端小程序的包内都必须存在下列 5 个文件，且路径**严格一致**
--   （微信包内资源不能跨小程序共享，两个包各存一份）：
--     miniapp-user/assets/product/pulisi-pure.webp        （346x512, 约 21 KB）
--     miniapp-user/assets/product/pulisi-spring.webp      （346x512, 约 20 KB）
--     miniapp-user/assets/product/pulisi-mineral.webp     （346x512, 约 22 KB）
--     miniapp-user/assets/product/laoshan-mineral.webp    （356x436, 约 14 KB）
--     miniapp-user/assets/product/laoshan-spring.webp     （356x436, 约 16 KB）
--     miniapp-delivery/assets/product/  ← 同名 5 份
--   生成脚本（可复跑）：assets/product-preset-sources/tools/build-brand-images.py
--
-- ⚠️ 版权提示：这批图采自品牌官网、**未经授权**，仅适用于开发 / 内测阶段。
--   正式对外上线前必须替换为自制图或取得授权 —— 用户已知情并选择「后期我再改」。
--
-- 【源图命名有历史错误，勿照抄文件名】
--   循环桶-天然矿泉水-崂山18.9L-绿标.jpg  → 实际画面是 3.78L 崂山山泉小桶（非 18.9L 循环桶）
--   循环桶-天然矿泉水-崂山18.9L-蓝桶.jpg  → 实际画面是 崂山山泉 大桶（循环桶）
--   循环桶-天然矿泉水-崂山18.9L-蓝桶2.jpg → 实际画面是 崂山矿泉水 大桶（循环桶）
--   本脚本的对应关系**以实际画面内容为准**。
--
-- 【执行方式】（必须指定库，脚本内不写 USE）
--   export MYSQL_PWD=<密码>
--   D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v55_platform_product_brand_images.sql"
--   ⚠️ 不要用 `cmd /c "mysql ... < file.sql"`（本仓 Bash 沙箱会拦 cmd.exe）。
--   ⚠️ 不要用 PowerShell 管道（逐行处理会破坏多字节字符）。
--   执行前先备份：mysqldump 到 backup/。
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

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：断言所需列存在（只断言，不改 DDL）
-- -----------------------------------------------------------------------------
SET @col_cnt := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'product'
                   AND COLUMN_NAME IN ('owner_station_id','category','brand','name',
                                       'image_object_name','update_time'));
SELECT IF(@col_cnt = 6,
          'OK: product 所需 6 列齐备（纯数据迁移，无需 DDL）',
          CONCAT('ABORT: product 缺列（命中 ', @col_cnt, '/6）')) AS column_check;

-- -----------------------------------------------------------------------------
-- 第 2 步：执行前留痕（现图分布 + 5 个目标行是否都在）
-- -----------------------------------------------------------------------------
SELECT '--- 执行前 A：通用库图片字段分布（预期：52 行同为 barrel-water.webp）---' AS step;
SELECT image_object_name, COUNT(*) AS n
FROM product WHERE owner_station_id IS NULL
GROUP BY image_object_name ORDER BY n DESC;

SELECT '--- 执行前 B：5 个目标行（应恰好命中 5 行）---' AS step;
SELECT id, brand, name, image_object_name
FROM product
WHERE owner_station_id IS NULL AND category = 1
  AND (   (brand = '普利思' AND name LIKE '%纯净水%')
       OR (brand = '普利思' AND name LIKE '%天然泉水%')
       OR (brand = '普利思' AND name LIKE '%天然矿泉水%')
       OR (brand = '崂山'   AND name LIKE '%天然矿泉水%')
       OR (brand = '崂山'   AND name LIKE '%山泉水%') )
ORDER BY brand, id;

SET @target_cnt := (SELECT COUNT(*) FROM product
                    WHERE owner_station_id IS NULL AND category = 1
                      AND (   (brand = '普利思' AND name LIKE '%纯净水%')
                           OR (brand = '普利思' AND name LIKE '%天然泉水%')
                           OR (brand = '普利思' AND name LIKE '%天然矿泉水%')
                           OR (brand = '崂山'   AND name LIKE '%天然矿泉水%')
                           OR (brand = '崂山'   AND name LIKE '%山泉水%') ));
SELECT IF(@target_cnt = 5,
          'OK: 5 个目标行全部命中',
          CONCAT('ABORT: 目标行命中 ', @target_cnt, ' 行（预期 5），请先核对库内容')) AS target_check;

SET @abort2 := IF(@target_cnt <> 5, 'SELECT * FROM __ABORT_TARGET_ROW_MISMATCH__', 'SELECT 1');
PREPARE st_abort2 FROM @abort2;
EXECUTE st_abort2;
DEALLOCATE PREPARE st_abort2;

-- -----------------------------------------------------------------------------
-- 第 3 步：换图（5 行）
-- -----------------------------------------------------------------------------
SELECT '--- 第 3 步：逐行换图（幂等重跑时为 0）---' AS step;

-- 普利思 纯净水
UPDATE product SET image_object_name = '/assets/product/pulisi-pure.webp', update_time = NOW()
WHERE owner_station_id IS NULL AND category = 1 AND brand = '普利思'
  AND name LIKE '%纯净水%'
  AND image_object_name <> '/assets/product/pulisi-pure.webp';
SELECT CONCAT('  普利思 纯净水      -> pulisi-pure.webp    影响 ', ROW_COUNT(), ' 行') AS r;

-- 普利思 天然泉水
UPDATE product SET image_object_name = '/assets/product/pulisi-spring.webp', update_time = NOW()
WHERE owner_station_id IS NULL AND category = 1 AND brand = '普利思'
  AND name LIKE '%天然泉水%'
  AND image_object_name <> '/assets/product/pulisi-spring.webp';
SELECT CONCAT('  普利思 天然泉水    -> pulisi-spring.webp  影响 ', ROW_COUNT(), ' 行') AS r;

-- 普利思 天然矿泉水
UPDATE product SET image_object_name = '/assets/product/pulisi-mineral.webp', update_time = NOW()
WHERE owner_station_id IS NULL AND category = 1 AND brand = '普利思'
  AND name LIKE '%天然矿泉水%'
  AND image_object_name <> '/assets/product/pulisi-mineral.webp';
SELECT CONCAT('  普利思 天然矿泉水  -> pulisi-mineral.webp 影响 ', ROW_COUNT(), ' 行') AS r;

-- 崂山 天然矿泉水（源图：循环桶-天然矿泉水-崂山18.9L-蓝桶2.jpg = 崂山矿泉水 大桶）
UPDATE product SET image_object_name = '/assets/product/laoshan-mineral.webp', update_time = NOW()
WHERE owner_station_id IS NULL AND category = 1 AND brand = '崂山'
  AND name LIKE '%天然矿泉水%'
  AND image_object_name <> '/assets/product/laoshan-mineral.webp';
SELECT CONCAT('  崂山 天然矿泉水    -> laoshan-mineral.webp 影响 ', ROW_COUNT(), ' 行') AS r;

-- 崂山 山泉水（源图：循环桶-天然矿泉水-崂山18.9L-蓝桶.jpg = 崂山山泉 大桶）
UPDATE product SET image_object_name = '/assets/product/laoshan-spring.webp', update_time = NOW()
WHERE owner_station_id IS NULL AND category = 1 AND brand = '崂山'
  AND name LIKE '%山泉水%'
  AND image_object_name <> '/assets/product/laoshan-spring.webp';
SELECT CONCAT('  崂山 山泉水        -> laoshan-spring.webp  影响 ', ROW_COUNT(), ' 行') AS r;

-- -----------------------------------------------------------------------------
-- 第 4 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：5 行的图（口径：普利思 3 行 + 崂山 2 行）---' AS step;
SELECT id, brand, name, spec, price, deposit, image_object_name
FROM product
WHERE owner_station_id IS NULL
  AND image_object_name IN ('/assets/product/pulisi-pure.webp',
                            '/assets/product/pulisi-spring.webp',
                            '/assets/product/pulisi-mineral.webp',
                            '/assets/product/laoshan-mineral.webp',
                            '/assets/product/laoshan-spring.webp')
ORDER BY brand, id;

SELECT '--- 校验 B：通用库全量图分布（应 47 行通用图 + 5 行品牌图 = 52）---' AS step;
SELECT image_object_name, COUNT(*) AS n
FROM product WHERE owner_station_id IS NULL
GROUP BY image_object_name ORDER BY n DESC;

SELECT '--- 校验 C：行数守恒（应仍为 52，本迁移只 UPDATE 不增删）---' AS step;
SELECT COUNT(*) AS platform_rows FROM product WHERE owner_station_id IS NULL;

SELECT '--- 校验 D：站内自定义商品未被波及（owner_station_id 非空的行不应带平台图）---' AS step;
SELECT id, owner_station_id, name, image_object_name
FROM product WHERE owner_station_id IS NOT NULL ORDER BY id;

SELECT '--- 校验 E：图片路径一致性（所有本地图都应以 /assets/product/ 开头）---' AS step;
SELECT image_object_name, COUNT(*) AS n
FROM product WHERE owner_station_id IS NULL AND image_object_name LIKE '/%'
GROUP BY image_object_name;

SELECT 'V55 完成：5 行已换成品牌官网图，其余 47 行保持通用图（素材缺口见脚本头注释）' AS note;
