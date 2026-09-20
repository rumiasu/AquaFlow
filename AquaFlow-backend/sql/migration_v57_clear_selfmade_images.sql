-- =============================================================================
-- V57: 清空「平台自制图」的引用 —— 商品图口径收紧为「只用品牌官网实拍图」
-- =============================================================================
--
-- 【背景】用户 2026-09-20 指令：「只要官网图片，你自己加的先不要」
--
--   两份自制图（{@code /assets/product/barrel-water.webp} 通用桶装水、
--   {@code /assets/product/barrel-purified.webp} 饮用纯净水桶）已连同
--   **两端小程序包内的物理文件**一起删除（各 2 份，共 4 个文件）：
--     miniapp-user/assets/product/{barrel-water,barrel-purified}.webp
--     miniapp-delivery/assets/product/{barrel-water,barrel-purified}.webp
--   同时 {@code constant/ProductImageKeys} 的预设清单已清空（该面板暂时为空）。
--
--   但数据库里还有 **47 行** 的 {@code image_object_name} 指着这两个路径 ——
--   文件既然没了，留着就是**死链**：将来把这些商品重新上架时，界面会去加载一个
--   不存在的包内资源（表现为静默无图，不是报错，很难查）。
--   ⇒ 本迁移把它们**置为 NULL**，让"没有图"这件事在数据层就如实反映出来。
--
-- 【影响范围】只有 47 行，且**全部处于已下架状态**（{@code status = 0}）——
--   在架的 5 行用的是品牌官网图，不受影响、也不会被本脚本碰到。
--   置 NULL 后这些行就是"没配图的商品"，与它们当前"已下架"的状态是一致的。
--
-- 【为什么不用物理删除行】{@code product.id} 是 15 张业务表的锚点
--   （order_item / deposit_record / ticket_record / customer_barrel_* …），
--   仓规明确**永不物理删商品**。这里只清一个展示字段。
--
-- 【幂等】UPDATE 带 {@code image_object_name IS NOT NULL} 条件 → 二次执行影响 0 行。
--
-- 【回滚】
--   -- 若要把旧图挂回去（需先把文件恢复：见 assets/product-preset-sources/tools/）：
--   UPDATE product SET image_object_name = '/assets/product/barrel-water.webp', update_time = NOW()
--   WHERE owner_station_id IS NULL AND image_object_name IS NULL AND status = 0;
--   ⚠️ 回滚前必须先确认两端包内的文件确实存在，否则等于把死链写回去。
--
-- 【执行方式】（必须指定库，脚本内不写 USE）
--   export MYSQL_PWD=<密码>
--   D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v57_clear_selfmade_images.sql"
--   ⚠️ 不要用 `cmd /c "mysql ... < file.sql"`（本仓 Bash 沙箱会拦 cmd.exe）。
--   ⚠️ 不要用 PowerShell 管道（逐行处理会破坏多字节字符）。
--   执行前先备份：mysqldump 到 backup/。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：防误库预检
-- -----------------------------------------------------------------------------
SET @tbl_cnt := (SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('product','inventory','orders'));
SELECT IF(@tbl_cnt = 3,
          'OK: 目标库校验通过（product/inventory/orders 均在）',
          CONCAT('ABORT: 疑似不是 aquaflow 库（关键表命中 ', @tbl_cnt, '/3），已终止')) AS precheck;

SET @abort := IF(@tbl_cnt <> 3, 'SELECT * FROM __ABORT_WRONG_DATABASE__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：执行前留痕 —— 待清理的行 + 确认它们都已下架
-- -----------------------------------------------------------------------------
SELECT '--- 清理前：指着已删除文件的行（应 47 行，且 status 全为 0）---' AS step;
SELECT image_object_name, COUNT(*) AS n, SUM(status = 1) AS on_shelf
FROM product
WHERE owner_station_id IS NULL
  AND image_object_name IN ('/assets/product/barrel-water.webp',
                            '/assets/product/barrel-purified.webp')
GROUP BY image_object_name;

-- 安全闸：若这些行里还有在架的，说明"在架商品必须都有官网图"这条口径被破坏了，
-- 直接终止，避免把在架商品悄悄变成无图。
SET @on_shelf_of_deleted := (SELECT COUNT(*) FROM product
                             WHERE owner_station_id IS NULL AND status = 1
                               AND image_object_name IN ('/assets/product/barrel-water.webp',
                                                         '/assets/product/barrel-purified.webp'));
SELECT IF(@on_shelf_of_deleted = 0,
          'OK: 待清理的行全部已下架，置空安全',
          CONCAT('ABORT: 有 ', @on_shelf_of_deleted, ' 行仍在架却指着已删除的图，请先核对')) AS safety_check;

SET @abort2 := IF(@on_shelf_of_deleted <> 0, 'SELECT * FROM __ABORT_ON_SHELF_USES_DELETED_IMAGE__', 'SELECT 1');
PREPARE st_abort2 FROM @abort2;
EXECUTE st_abort2;
DEALLOCATE PREPARE st_abort2;

-- -----------------------------------------------------------------------------
-- 第 2 步：置空
-- -----------------------------------------------------------------------------
UPDATE product
SET image_object_name = NULL, update_time = NOW()
WHERE owner_station_id IS NULL
  AND image_object_name IN ('/assets/product/barrel-water.webp',
                            '/assets/product/barrel-purified.webp');

SELECT CONCAT('  置空完成，影响 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 3 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：全仓不应再有指着自制图的引用（应返回空集）---' AS step;
SELECT id, brand, name, image_object_name FROM product
WHERE image_object_name LIKE '/assets/product/barrel-%';

SELECT '--- 校验 B：图片字段分布（在架 5 行必须都持有官网图；下架行应为 NULL）---' AS step;
SELECT image_object_name, status, COUNT(*) AS n
FROM product WHERE owner_station_id IS NULL
GROUP BY image_object_name, status
ORDER BY status DESC, n DESC;

SELECT '--- 校验 C：在架行必须都有图（应返回空集）---' AS step;
SELECT id, brand, name, image_object_name FROM product
WHERE owner_station_id IS NULL AND status = 1 AND image_object_name IS NULL;

SELECT '--- 校验 D：行数守恒（应仍为 52，本迁移只 UPDATE 不增删）---' AS step;
SELECT COUNT(*) AS platform_rows,
       SUM(status = 1) AS on_shelf,
       SUM(status = 0) AS delisted
FROM product WHERE owner_station_id IS NULL;

SELECT 'V57 完成：自制图引用已全部清空（47 行置 NULL），在架 5 行仍持有品牌官网图' AS note;
