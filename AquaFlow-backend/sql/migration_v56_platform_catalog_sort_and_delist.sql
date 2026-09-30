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
--    ✅ **重跑安全**：本脚本已带哨兵（见下面【幂等 + 重跑契约】）—— 你置回 1 之后**重跑不会再
--       把它们下架**，并会在输出里打印 `skip: 本迁移已执行过…`。
--
-- -----------------------------------------------------------------------------
-- 【幂等 + 重跑契约（F-27，2026-09-30 加 · 不要删）】
--   ⚠️ **契约：重跑不会撤销人工上架。** 本脚本自带两个**独立**哨兵，各自门住自己那一步：
--   · 第 1 步哨兵：若平台库的行 `sort` **已经**等于目标序列（rn × 10）⇒ 打印
--     `skip: 排架已是目标序列`，**不重排、也不刷 `update_time`**。
--   · 第 2 步哨兵：若平台库**已经存在 `status = 0` 的行** ⇒ 认定"本迁移已执行过" ⇒
--     打印 `skip: 本迁移已执行过`，**不重新下架** —— 于是本文件头部与 `sql/README.md`
--     教你的"要恢复售卖把那两行 `status` 置回 1"不会被下一次重跑悄悄抹掉，且有提示。
--   为什么**不**用"47 行无品牌图的行都是 `status = 0`"当哨兵：那恰好是"人工把那两行置回 1"
--     之后就失效的判据（45 行 0 + 2 行 1）⇒ 重跑会**重新下架**，正是 F-27 要防的事。
--   为什么**不**做"只处理本次迁移范围内的行"的条件：**没有任何一列能表达"本次迁移范围"**；
--     而按图片判定的条件本身不稳定（v57 已把那 47 行的平台自制图置 NULL ⇒ `IS NULL`
--     与"无品牌图"恒等）⇒ 该条件只会退化成现状（下架所有无品牌图的行），不解决问题。
--   唯一会重新下架的例外：把**全部**下架行都手工恢复上架（`status = 0` 计数归零）——
--     那等于把本次迁移整体撤销，此时重跑再执行一次，属预期行为。
--   · 旧有的幂等保证仍在：第 1 步重排结果相同、第 2 步 UPDATE 带 `status <> 0` 条件。
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

-- 哨兵①（F-27）：先数"sort 与目标序列不一致的行"。为 0 ⇒ 本迁移已执行过 ⇒ 第 1 步整体 skip。
--   判据只用 `sort`（人工经营动的是 `status`，不会让这里重新变成非 0）—— 见文件头重跑契约。
SET @sort_mismatch := (SELECT COUNT(*) FROM product p
                       JOIN tmp_product_sort t ON t.id = p.id
                       WHERE p.owner_station_id IS NULL AND p.sort <> t.rn * 10);
SELECT CONCAT('  与目标序列不一致的行数 = ', @sort_mismatch,
              '（0 = 排架已是目标序列，本次不重排、不刷 update_time）') AS note;

SET @s1 := IF(@sort_mismatch = 0,
  "SELECT 'skip: 排架已是目标序列（本迁移已执行过），第 1 步不重排' AS note",
  "UPDATE product p JOIN tmp_product_sort t ON t.id = p.id SET p.sort = t.rn * 10, p.update_time = NOW() WHERE p.sort <> t.rn * 10");
PREPARE st_s1 FROM @s1; EXECUTE st_s1; DEALLOCATE PREPARE st_s1;

SELECT IF(@sort_mismatch = 0,
          '  第 1 步：skip（未重排、未刷 update_time）',
          CONCAT('  重排完成，影响 ', @sort_mismatch, ' 行（= 上面那个不一致行数；已排好的行不刷 update_time）')) AS note;

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

-- 哨兵②（F-27）：平台库**已有下架行** ⇒ 认定本迁移已执行过 ⇒ 第 2 步整体 skip
--   （于是人工把 product 1/2 的 status 置回 1 之后，重跑**不会**再把它们下架）
SET @delisted_exists := (SELECT COUNT(*) FROM product WHERE owner_station_id IS NULL AND status = 0);
SET @to_delist := (SELECT COUNT(*) FROM product
                   WHERE owner_station_id IS NULL AND status <> 0
                     AND (image_object_name IS NULL
                          OR image_object_name NOT IN ('/assets/product/pulisi-pure.webp',
                                                       '/assets/product/pulisi-spring.webp',
                                                       '/assets/product/pulisi-mineral.webp',
                                                       '/assets/product/laoshan-mineral.webp',
                                                       '/assets/product/laoshan-spring.webp')));
SELECT CONCAT('  平台库已下架行数 = ', @delisted_exists,
              '；本次待下架行数 = ', @to_delist) AS note;

-- ⚠️ 必须先判 NULL：`NOT IN` 遇到 NULL 返回 NULL（不是 TRUE），
--    漏了 `IS NULL` 这一支会让「图片字段为空的行」被漏掉、继续留在货架上。
--    下面这条 UPDATE 只在"平台库还没有任何下架行"（= 本迁移没跑过）时才生成。
SET @s2 := IF(@delisted_exists > 0,
  "SELECT 'skip: 本迁移已执行过（平台库已有下架行），第 2 步不重新下架；人工上架的行保持原样' AS note",
  "UPDATE product SET status = 0, update_time = NOW() WHERE owner_station_id IS NULL AND status <> 0 AND (image_object_name IS NULL OR image_object_name NOT IN ('/assets/product/pulisi-pure.webp','/assets/product/pulisi-spring.webp','/assets/product/pulisi-mineral.webp','/assets/product/laoshan-mineral.webp','/assets/product/laoshan-spring.webp'))");
PREPARE st_s2 FROM @s2; EXECUTE st_s2; DEALLOCATE PREPARE st_s2;

SELECT IF(@delisted_exists > 0,
          '  第 2 步：skip（未改动任何 status）',
          CONCAT('  下架完成，影响 ', @to_delist, ' 行（= 上面那个待下架行数）')) AS note;

-- -----------------------------------------------------------------------------
-- 第 3 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：货架全貌（按 sort 升序，应同品牌连续、品牌按拼音序）---' AS step;
SELECT id, brand, spec, category, status,
       CASE WHEN status = 1 THEN '在架' ELSE '已下架' END AS state,
       image_object_name
FROM product WHERE owner_station_id IS NULL ORDER BY sort, id;

SELECT '--- 校验 B：在架行数与分类（本迁移刚跑完应恰好 5 行：普利思 3 + 崂山 2）---' AS step;
-- ⚠️ 若你**手工**把某行 status 置回 1（本文件头部就是这么教的），这里会 > 5 行 ——
--    那是你的经营动作、不是错误；判据是"在架的行都有品牌官网图"（校验 C）。
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

SELECT CONCAT('V56 复核完毕：排架',
              IF(@sort_mismatch = 0, '已是目标序列（skip，未刷 update_time）', '已按品牌重排'),
              '；下架', IF(@delisted_exists > 0,
                           CONCAT('本迁移已执行过（skip，人工上架保持原样；当前在架 ', (SELECT COUNT(*) FROM product WHERE owner_station_id IS NULL AND status = 1), ' 行）'),
                           '已完成')) AS note;
