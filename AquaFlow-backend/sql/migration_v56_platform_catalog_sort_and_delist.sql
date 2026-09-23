-- =============================================================================
-- V56: 平台通用库货架整理 —— ① 按品牌重排 sort  ② 无品牌图的行下架
-- =============================================================================
--
-- 【背景】用户 2026-09-20 指令：「做一下按照品牌排序吧，现在有些乱。还有剩下的没有图的
--   是都不好找吗，那就先下架吧，目前先只上架有图的」
--
--   现状（v55 之后）：通用库 52 行、34 个品牌，`sort` 值是历史累加出来的
--   （v52 的 10–280 段 + v53 的 290–450 段 + 一次性桶的 600–680 段），
--   于是**同一个品牌被排到了货架两端**，且 `sort=600` 有**重复值**（排序不稳定）：
--     乐百氏 出现在 260 与 450；农夫山泉 在 210 与 600；怡宝 在 240 与 620；
--     泉阳泉 在 390 / 630 / 640；艺韵 在 360 与 670。
--   而 v55 只让 5 行（普利思 3 + 崂山 2）拿到了品牌官网图，其余 47 行仍是通用图。
--
-- -----------------------------------------------------------------------------
-- 【本迁移做两件事】
--
--   ① 按**品牌**重排 `sort`（品牌拼音升序；同品牌内：桶装水在前 → 价格升序 → id）
--      用 `FIELD(brand, …)` 显式声明品牌顺序 —— 不依赖数据库排序规则，
--      中文拼音序在 utf8mb4_general_ci 下并不可靠，显式列表才是可复现的。
--
--   ② 把**没有品牌官网图**的 47 行 `status` 置 0（下架），只保留 5 行在架：
--        普利思 纯净水 / 天然泉水 / 天然矿泉水、崂山 矿泉水 / 山泉水
--
-- -----------------------------------------------------------------------------
-- 【下架（status = 0）在这个系统里的确切语义 —— 读代码得到，不是推测】
--
--   · `ProductMapper.softDeleteOwned` 用的就是 `status = 0`，注释写明「软删除(停用/下架)」
--     —— 这是本仓既有的下架口径，**不做物理删除**（`product.id` 是 15 张业务表的锚点，
--     历史订单 / 押金条 / 水票 / 桶权益都靠它留痕）。
--
--   · **客户端商城**：`ProductMapper.listSellableByStation` /
--     `listSellableByStationWithInventory` 的 WHERE 都带 `i.enabled = 1 and p.status = 1`
--     → 下架后客户端**立刻不再售卖**。✅
--
--   · **站长端「选品清单」**：`CatalogServiceImpl.listCatalog` 直接返回
--     `ProductMapper.listWithInventory`，该 SQL **不过滤 `p.status`** ——
--     所以**只改数据不改代码的话，站长端仍会看到这 47 行**。
--     ⇒ 配套代码改动（本次同批提交）：
--       `CatalogServiceImpl.listCatalog` 增加一行过滤：
--         通用库商品必须 `status = 1`，或**本站已选用**（`inventoryId != null`，否则
--         站长看不到自己已上架的商品、无法管理库存）；本站自定义商品不受平台下架影响。
--
--   · **已选用（`inventory` 有行）的商品不受影响**：库存、价格覆盖、水票开关都在
--     `inventory` 上，与 `product.status` 无关；历史订单照常。
--
-- -----------------------------------------------------------------------------
-- ⚠️ 【必须知情的副作用】station 1 已经选用了 2 个商品，而它们都没品牌图、本次会被下架：
--        product_id = 1 → 农夫山泉 饮用天然水 19L 桶装水
--        product_id = 2 → 娃哈哈 饮用纯净水 18.9L 桶装水
--    下架后这 2 款会**从客户端商城消失**（因为商城判据是 `p.status = 1`），
--    站长端仍可见（属于「本站已选用」豁免）、库存与历史订单不受影响。
--    若希望它们继续售卖，把这两行的 `status` 单独置回 1 即可（见文末回滚段）。
--
-- -----------------------------------------------------------------------------
-- 【幂等】
--   · 第 1 步用临时表算行号后整体重写 `sort` —— 重跑结果相同（同一套排序键）。
--   · 第 2 步 UPDATE 带 `status <> 0` 条件 → 二次执行影响 0 行。
--
-- 【回滚】
--   -- 恢复全部上架（本迁移执行前 52 行 status 全为 1）：
--   UPDATE product SET status = 1, update_time = NOW() WHERE owner_station_id IS NULL;
--   -- 只恢复某几个：
--   UPDATE product SET status = 1 WHERE owner_station_id IS NULL AND id IN (1, 2);
--   （sort 若也要回退，用备份备份文件还原，或按 v52/v53 的 sort 值手改。）
--
-- 【执行方式】（必须指定库，脚本内不写 USE）
--   export MYSQL_PWD=<密码>
--   D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v56_platform_catalog_sort_and_delist.sql"
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
-- 第 1 步：按品牌重排 sort
-- -----------------------------------------------------------------------------
SELECT '--- 第 1 步：重排前（品牌交错，可见同一品牌被排到货架两端）---' AS step;
SELECT id, brand, spec, sort FROM product WHERE owner_station_id IS NULL ORDER BY sort, id;

DROP TEMPORARY TABLE IF EXISTS tmp_product_sort;
CREATE TEMPORARY TABLE tmp_product_sort (
    id BIGINT NOT NULL PRIMARY KEY,
    rn   INT  NOT NULL
);

INSERT INTO tmp_product_sort (id, rn)
SELECT id,
       ROW_NUMBER() OVER (
           ORDER BY FIELD(brand,
                          '阿尔卑斯','爱茶说','百脉泉','百圣泉','趵突泉','冰露','畅饮吧',
                          '涵露','好山好水','恒大','景田百岁山','崂山','乐百氏','农夫山泉',
                          '普利思','七星台','惬尔','泉城茗水','泉娃','泉阳泉','雀巢',
                          '润田翠','山下泉','圣境甘泉','泰山甘泉','天地矿泉','娃哈哈',
                          '象牙山冰点','雪峪冰点','雪峪长白山泉','一山一水','怡宝','艺韵','云恬'),
                    category,          -- 同品牌内：桶装水(1) 排在一次性桶(2) 之前
                    price,             -- 再按参考价升序
                    id)
FROM product
WHERE owner_station_id IS NULL;

-- 品牌枚举必须全部命中 FIELD 列表，否则会排到最前（FIELD 返回 0）
SET @unknown_brand := (SELECT COUNT(DISTINCT brand) FROM product
                       WHERE owner_station_id IS NULL
                         AND FIELD(brand,
                                   '阿尔卑斯','爱茶说','百脉泉','百圣泉','趵突泉','冰露','畅饮吧',
                                   '涵露','好山好水','恒大','景田百岁山','崂山','乐百氏','农夫山泉',
                                   '普利思','七星台','惬尔','泉城茗水','泉娃','泉阳泉','雀巢',
                                   '润田翠','山下泉','圣境甘泉','泰山甘泉','天地矿泉','娃哈哈',
                                   '象牙山冰点','雪峪冰点','雪峪长白山泉','一山一水','怡宝','艺韵','云恬') = 0);
SELECT IF(@unknown_brand = 0,
          'OK: 全部品牌都在排序列表中',
          CONCAT('ABORT: 有 ', @unknown_brand, ' 个品牌不在排序列表中，请先补进 FIELD 参数')) AS brand_check;

SET @abort2 := IF(@unknown_brand <> 0, 'SELECT * FROM __ABORT_UNKNOWN_BRAND__', 'SELECT 1');
PREPARE st_abort2 FROM @abort2;
EXECUTE st_abort2;
DEALLOCATE PREPARE st_abort2;

UPDATE product p
JOIN tmp_product_sort t ON t.id = p.id
SET p.sort = t.rn * 10, p.update_time = NOW();

SELECT CONCAT('  重排完成，影响 ', ROW_COUNT(), ' 行') AS note;

DROP TEMPORARY TABLE tmp_product_sort;

-- -----------------------------------------------------------------------------
-- 第 2 步：下架「没有品牌官网图」的行（保留 v55 换过图的 5 行）
-- -----------------------------------------------------------------------------
SELECT '--- 第 2 步：下架前，上架中的行数与品牌图持有数 ---' AS step;
SELECT COUNT(*) AS on_sale_all FROM product WHERE owner_station_id IS NULL AND status = 1;
SELECT COUNT(*) AS with_brand_image FROM product
WHERE owner_station_id IS NULL AND status = 1
  AND image_object_name IN ('/assets/product/pulisi-pure.webp',
                            '/assets/product/pulisi-spring.webp',
                            '/assets/product/pulisi-mineral.webp',
                            '/assets/product/laoshan-mineral.webp',
                            '/assets/product/laoshan-spring.webp');

-- ⚠️ 必须先判 NULL：`NOT IN` 遇到 NULL 返回 NULL（不是 TRUE），
--    漏了 `IS NULL` 这一支会让「图片字段为空的行」被漏掉、继续留在货架上。
UPDATE product
SET status = 0, update_time = NOW()
WHERE owner_station_id IS NULL
  AND status <> 0
  AND (image_object_name IS NULL
       OR image_object_name NOT IN ('/assets/product/pulisi-pure.webp',
                                    '/assets/product/pulisi-spring.webp',
                                    '/assets/product/pulisi-mineral.webp',
                                    '/assets/product/laoshan-mineral.webp',
                                    '/assets/product/laoshan-spring.webp'));

SELECT CONCAT('  下架完成，影响 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 3 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：货架全貌（按 sort 升序，应同品牌连续、品牌按拼音序）---' AS step;
SELECT id, brand, spec, category, status,
       CASE WHEN status = 1 THEN '在架' ELSE '已下架' END AS state,
       image_object_name
FROM product WHERE owner_station_id IS NULL ORDER BY sort, id;

SELECT '--- 校验 B：在架行数与分类（应恰好 5 行：普利思 3 + 崂山 2）---' AS step;
SELECT COUNT(*) AS on_sale_total FROM product WHERE owner_station_id IS NULL AND status = 1;
SELECT brand, COUNT(*) AS n FROM product
WHERE owner_station_id IS NULL AND status = 1 GROUP BY brand ORDER BY brand;

SELECT '--- 校验 C：在架的行必须都有品牌图（应返回空集）---' AS step;
SELECT id, brand, name, image_object_name FROM product
WHERE owner_station_id IS NULL AND status = 1
  AND (image_object_name IS NULL
       OR image_object_name NOT IN ('/assets/product/pulisi-pure.webp',
                                    '/assets/product/pulisi-spring.webp',
                                    '/assets/product/pulisi-mineral.webp',
                                    '/assets/product/laoshan-mineral.webp',
                                    '/assets/product/laoshan-spring.webp'));

SELECT '--- 校验 D：sort 无重复（应返回空集）---' AS step;
SELECT sort, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL
GROUP BY sort HAVING COUNT(*) > 1;

SELECT '--- 校验 E：行数守恒（应仍为 52，本迁移只 UPDATE 不增删）---' AS step;
SELECT COUNT(*) AS platform_rows FROM product WHERE owner_station_id IS NULL;

SELECT '--- 校验 F：⚠️ 已下架但本站已选用的商品（这些仍会出现在站长端，属豁免）---' AS step;
SELECT i.station_id, i.product_id, p.name, p.status
FROM inventory i JOIN product p ON p.id = i.product_id
WHERE p.owner_station_id IS NULL AND p.status = 0;

SELECT 'V56 完成：52 行按品牌重排；在架 5 行（均有品牌官网图），下架 47 行' AS note;
