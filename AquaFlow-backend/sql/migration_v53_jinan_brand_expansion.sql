-- =============================================================================
-- V53: 济南水站货架扩充（品牌全覆盖）+ 一次性桶按「瓶装水」归类
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   export MYSQL_PWD=<密码>; D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v53_jinan_brand_expansion.sql"
--   ⚠️ 不要用 `cmd /c "mysql ... < file.sql"`（本仓 Bash 沙箱会拦 cmd.exe）。
--   ⚠️ 不要用 PowerShell 管道（逐行处理会破坏多字节字符）。
-- 执行前先备份：mysqldump 到 backup/。
--
-- ⚠️ 编号说明：**已先 `ls migration_v*.sql` 确认磁盘最大号为 v52**（v52 是我上一轮建的），
--    故本脚本编号 v53。（v38 与 v52 两次都因"凭 README 清单尾部猜编号"而撞号，
--    现在改为**只认磁盘上的最大编号**，清单更新滞后不影响判断。）
-- =============================================================================
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-19）
-- -----------------------------------------------------------------------------
--   v52 把济南主流桶装水补到 26 行。用户进一步要求「每个品牌能查到的都写进去」，
--   即把济南水站/配送商**明确在售**的品牌全部补进平台通用库。
--
--   本轮补齐 26 行（通用库 26 → 52 行），覆盖济南本地新品牌（云恬 / 畅饮吧 /
--   一山一水 / 山下泉 / 天地矿泉 / 圣境甘泉 / 惬尔 / 艺韵）与全国品牌
--   （雀巢 / 泉阳泉 / 恒大 / 雪峪 / 象牙山 / 润田翠 / 阿尔卑斯 / 乐百氏）。
--
-- -----------------------------------------------------------------------------
-- ★ 核心设计决定（用户拍板，2026-09-19）：一次性桶 → category = 2
-- -----------------------------------------------------------------------------
--   问题：一次性桶（PET 桶，免押金、不回收）此前**无法表达**。
--         `category = 1` 是**唯一**的桶判据 —— 押金填 0 也拦不住它：
--         系统照样把数量算进 totalNeededBuckets，建在途桶 → 送达建押金条(lot)
--         → 客户凭空获得桶权益，且手上没有可还的桶 → 每单生成一条**假桶异常单**。
--
--   用户方案：**一次性桶归入 category = 2（瓶装水）**。
--   已逐行核对 `category=1` 的全部 8 处后端判断点 + 1 处小程序判断点，
--   确认 category=2 时**全链路无桶逻辑**（证据表见下）：
--
--     | 位置                        | category=2 时的行为                          |
--     |-----------------------------|----------------------------------------------|
--     | OrderServiceImpl:258        | 不标记 needStationAsset                      |
--     | OrderServiceImpl:361        | 走 else：depositAmount += deposit×qty = 0    |
--     | OrderServiceImpl:367        | **跳过桶计数** → 不建在途桶                   |
--     | OrderServiceImpl:500        | delivery_bucket_qty = null（快照为 0）        |
--     | OrderServiceImpl:501        | first_barrel_order = false                    |
--     | OrderServiceImpl:521        | itemDeposit = deposit = 0                     |
--     | PaymentServiceImpl:1024     | 走 else：totalNonBarrelDeposit += 0          |
--     | miniapp create.js:470       | isBarrel = false → 押金 0                     |
--
--   ✅ 下单侧（已实测确认）：`category=2` 完全干净 —— 押金 0、
--      `delivery_bucket_qty = NULL`、`first_barrel_order = 0`。
--      回归用例：DisposableBarrelLedgerBoundaryIntegrationTest.completingDisposableBarrelOrderLeavesBarrelLedgerUntouched
--      的「下单侧」用例（绿）。
--
--   ⚠️⚠️ 但**配送侧并不干净**（2026-09-19 实测，推翻设计时的推断）：
--      `BarrelLedgerService.applyDelivery` 的「本单送出」是**按 order_item 数量**统计的，
--      **完全不看 category** —— `delivered = Σ order_item.quantity`，于是
--      `delta = delivered − returned − rightPurchase = 2 − 0 − 0 = +2` → `over += 2`
--      → `owed != 0` → 经 `orderBarrelExceptionService.recordReturn` **仍生成桶异常单**。
--      ⇒ 「归类为瓶装水」只挡住了下单侧，配送侧的桶账污染依然存在。
--
--      实测证据（DisposableBarrelLedgerBoundaryIntegrationTest 首轮）：
--        · 下单侧断言       绿   （押金 0 / bucket_qty NULL / first_barrel_order 0）
--        · 配送侧 over      红   over = 2（期望 0）
--        · 对照 category=1 首单  绿   权益 2 / 押金条 2 / over 0
--        · 对照 category=1 复购  绿   over 2 且异常单 1
--
--      ⇒ **这是既有潜伏缺陷，与本迁移无关**：真实库 `inventory` 只有 category=1 商品
--        （4 行），`customer_barrel_over` 也全是 category=1 且 `over_qty=0`，从未被触发。
--        但只要任何瓶装水 / 饮水器被售出并完成配送，就会污染桶账。
--        修复方向见该测试类上的 `@Disabled` 说明（A: applyDelivery 内按 category 过滤；
--        B: completeDelivery 以 delivery_bucket_qty == null 为闸）。
--
--      在修复落地前，本脚本入库的 10 行一次性桶**若被真实下单并完成配送**，
--      会各自产生一条假桶异常单。这一点必须知情。
--
--   ⚠️ 代价（需知情）：15L 桶在选品页的「瓶装水」筛选下会出现（后端文案
--      `ProductCategoryVO.getCategoryText()`：1=桶装水 / 2=瓶装水 / 3=饮水器）。
--      这是**有意**的取舍：本仓的 category 实质编码的是「**是否走桶循环**」，
--      而不是「瓶 vs 桶」的物理形态。
--
--   ⚠️ 若将来要按「桶装水」口径统计销量，一次性桶**不会**被计入 category=1
--      —— 这是该方案的已知代价，接受它换取零改动与零桶账污染。
--
-- -----------------------------------------------------------------------------
-- 收录规则（三条，本轮新立）
-- -----------------------------------------------------------------------------
--   1. **只收济南水站 / 济南配送商明确列出的品牌**。
--      排除：① 无济南在售证据的（康师傅 / 屈臣氏 / 昆仑山 —— 只有电商与超市渠道）；
--            ② B2B 定制/代工水厂（鹤知源·邹平 / 甘雨露·日照 / 普利森 / 达利园
--               —— 它们不是水站货架上的零售品）；
--            ③ 配送平台（水鲤鲤 / 好柿到家 / 水立多 —— 是渠道不是水品牌）；
--            ④ 其他城市本地品牌（北纬39度 / 汇云山泉·天津，悦玛泉·天津
--               —— 济南无在售证据）；
--            ⑤ 证据不足（艾珂 —— 仅出现于「主营品牌」列表，无规格无水种无价）。
--
--   2. **循环 / 一次性的划分口径：规格 < 16L 记为一次性桶（category=2, 押金 0）；
--      ≥ 16L 记为循环桶（category=1, 押金 50）**。
--      有直接证据的以证据为准（好山好水 15L、畅饮吧 15L 水立多明确标「免押金」）。
--
--   3. **压金一律 50**（百圣泉 30 除外，沿用 v52）；一次性桶押金一律 0。
--
-- -----------------------------------------------------------------------------
-- 取价规则（参考价性质，站长可用 inventory.sale_price 覆盖）
-- -----------------------------------------------------------------------------
--   优先序：① 济南本地水站在售报价 → ② 济南配送商列出的品牌（取同品牌同规格
--   其他城市在售报价作代理，脚本内逐行注明）→ ③ 同水种同规格的库内中位数。
--   ⚠️ **凡非济南本地直接报价的，均在 description 里注明来源**，不做无痕填充。
--
-- -----------------------------------------------------------------------------
-- 幂等（沿用 v52 的三条写法）
-- -----------------------------------------------------------------------------
--   · UPDATE 全部带「旧值」条件 → 二次执行影响 0 行。
--   · INSERT 用 `ON DUPLICATE KEY UPDATE id = id`（唯一键 `preset_uk` 撞上即空转）。
--     **不用 INSERT IGNORE** —— 那会把 NOT NULL 之类的真错误也吞成警告。
--   · 校验段只读，不改数据。
--
-- -----------------------------------------------------------------------------
-- 回滚（见文件末尾完整清单）
-- -----------------------------------------------------------------------------
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
-- 第 1 步：确认所需列存在（列早已存在，这里只断言，不改 DDL）
-- -----------------------------------------------------------------------------
SET @col_cnt := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'product'
                   AND COLUMN_NAME IN ('owner_station_id','category','brand','spec',
                                       'price','deposit','ticket_enabled','ticket_price','sort'));
SELECT IF(@col_cnt = 9,
          'OK: product 所需 9 列齐备（无需 DDL）',
          CONCAT('ABORT: product 缺列（命中 ', @col_cnt, '/9）')) AS column_check;

-- -----------------------------------------------------------------------------
-- 第 2 步：执行前留痕
-- -----------------------------------------------------------------------------
SELECT '--- 执行前：通用库商品（应为 26 行）---' AS step;
SELECT id, name, brand, spec, category, price, deposit, sort
FROM product WHERE owner_station_id IS NULL ORDER BY id;

SELECT '--- 执行前：站内自定义商品（本脚本应完全不碰）---' AS step;
SELECT id, owner_station_id, name FROM product WHERE owner_station_id IS NOT NULL ORDER BY id;

SELECT '--- 执行前：库存引用（本脚本应完全不碰）---' AS step;
SELECT id, station_id, product_id, quantity, enabled FROM inventory ORDER BY id;

-- -----------------------------------------------------------------------------
-- 第 3 步：把既有「好山好水 15L」从循环桶订正为一次性桶
--         ⚠️ 这是本脚本**唯一**改动存量商品属性的地方，理由：v52 把它按循环桶
--            入库（category=1 / 押金 50），但水立多章丘站在售页明确标注
--            「好山好水 饮用天然水 15升 **免押金**」—— 它是免押的一次性桶，
--            v52 的归类是错的（会按循环桶收 50 元押金并生成桶异常单）。
--         带旧值条件 → 二次执行影响 0 行。
-- -----------------------------------------------------------------------------
UPDATE product
SET name           = '好山好水 天然水 15L 一次性桶',
    category       = 2,
    deposit        = 0.00,
    sort           = 600,
    description    = '一次性桶（免押金、不回收）；规格 <16L 归入瓶装水类，不走桶循环'
WHERE owner_station_id IS NULL
  AND brand = '好山好水' AND spec = '15L'
  AND name = '好山好水 天然水 15L 桶装水'
  AND category = 1;

SELECT CONCAT('第 3 步完成：好山好水 15L 订正为一次性桶，影响 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 4 步-A：插入循环桶 17 行（category=1，押金 50）
--            ON DUPLICATE KEY UPDATE id = id → 撞 preset_uk 就空转（幂等）
-- -----------------------------------------------------------------------------
INSERT INTO product
    (owner_station_id, name, category, brand, spec, image_object_name, description,
     price, deposit, max_per_order, status, sort, ticket_enabled, ticket_price)
VALUES
    -- ===== 济南本地新品牌 =====
    (NULL, '云恬 天然水 18.9L 桶装水',        1, '云恬',       '18.9L', '/assets/product/barrel-water.webp', '济南本地自有品牌（水鲤鲤/好柿到家）；长清崮云湖 360 米深层山泉，弱碱 pH7.0-7.6。⚠️ 参考价按库内天然水同规格中位推算，该站未公开报价', 15.00, 50.00, 10, 1, 290, 1, 15.00),
    (NULL, '一山一水 饮用天然水 18.9L 桶装水', 1, '一山一水',   '18.9L', '/assets/product/barrel-water.webp', '章丘站在售；⚠️ 参考价按库内天然水同规格中位推算，该站仅列品牌未列价', 15.00, 50.00, 10, 1, 300, 1, 15.00),
    (NULL, '一山一水 天然矿泉水 18.9L 桶装水', 1, '一山一水',   '18.9L', '/assets/product/barrel-water.webp', '章丘站在售；⚠️ 参考价按库内矿泉水同规格中位推算，该站仅列品牌未列价', 18.00, 50.00, 10, 1, 310, 1, 18.00),
    (NULL, '山下泉 金典矿泉水 18.9L 桶装水',   1, '山下泉',     '18.9L', '/assets/product/barrel-water.webp', '历城站在售；⚠️ 参考价按库内矿泉水同规格中位推算，该站仅列品牌未列价', 18.00, 50.00, 10, 1, 320, 1, 18.00),
    (NULL, '天地矿泉 天然矿泉水 16.8L 桶装水', 1, '天地矿泉',   '16.8L', '/assets/product/barrel-water.webp', '历城站在售；16.8L 中桶；⚠️ 参考价按库内矿泉水同规格中位推算，该站仅列品牌未列价', 18.00, 50.00, 10, 1, 330, 1, 18.00),
    (NULL, '圣境甘泉 天然矿泉水 18.9L 桶装水', 1, '圣境甘泉',   '18.9L', '/assets/product/barrel-water.webp', '济南享堂水站在售报价 20 元/桶', 20.00, 50.00, 10, 1, 340, 1, 20.00),
    (NULL, '惬尔 矿泉水 18.9L 桶装水',         1, '惬尔',       '18.9L', '/assets/product/barrel-water.webp', '济南享堂水站在售报价 12 元/桶', 12.00, 50.00, 10, 1, 350, 1, 12.00),
    (NULL, '艺韵 矿泉水 18.9L 桶装水',         1, '艺韵',       '18.9L', '/assets/product/barrel-water.webp', '济南享堂水站在售报价 10 元/桶', 10.00, 50.00, 10, 1, 360, 1, 10.00),
    -- ===== 全国品牌（济南在售）=====
    (NULL, '雀巢 包装饮用水 18.9L 桶装水',     1, '雀巢',       '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；⚠️ 参考价取自同品牌同规格其他城市在售报价', 26.00, 50.00, 10, 1, 370, 1, 26.00),
    (NULL, '雀巢 纯净水 18.9L 桶装水',         1, '雀巢',       '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；⚠️ 参考价取自同品牌同规格其他城市在售报价', 24.00, 50.00, 10, 1, 380, 1, 24.00),
    (NULL, '泉阳泉 长白山天然矿泉水 18.9L 桶装水', 1, '泉阳泉', '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；长白山天然矿泉水；⚠️ 参考价取自同品牌同规格其他城市在售报价', 35.00, 50.00, 10, 1, 390, 1, 35.00),
    (NULL, '恒大 饮用天然矿泉水 17L 桶装水',   1, '恒大',       '17L',   '/assets/product/barrel-water.webp', '济南配送商主营品牌；17L 规格；⚠️ 参考价取自同品牌同规格其他城市在售报价', 30.00, 50.00, 10, 1, 400, 1, 30.00),
    (NULL, '恒大 纯净水 17L 桶装水',           1, '恒大',       '17L',   '/assets/product/barrel-water.webp', '济南配送商主营品牌；17L 规格；⚠️ 参考价取自同品牌同规格其他城市在售报价', 22.00, 50.00, 10, 1, 410, 1, 22.00),
    (NULL, '雪峪长白山泉 天然矿泉水 18.9L 桶装水', 1, '雪峪长白山泉', '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；长白山水系；⚠️ 参考价取自同品牌同规格其他城市在售报价', 22.00, 50.00, 10, 1, 420, 1, 22.00),
    (NULL, '雪峪冰点 纯净水 18.9L 桶装水',     1, '雪峪冰点',   '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；⚠️ 参考价取自同品牌同规格其他城市在售报价', 15.00, 50.00, 10, 1, 430, 1, 15.00),
    (NULL, '象牙山冰点 纯净水 18.9L 桶装水',   1, '象牙山冰点', '18.9L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；⚠️ 参考价取自同品牌同规格其他城市在售报价', 18.00, 50.00, 10, 1, 440, 1, 18.00),
    (NULL, '乐百氏 饮用水 17.5L 桶装水',       1, '乐百氏',     '17.5L', '/assets/product/barrel-water.webp', '济南配送商主营品牌；17.5L 规格；⚠️ 参考价取自同品牌同规格其他城市在售报价', 24.00, 50.00, 10, 1, 450, 1, 24.00)
ON DUPLICATE KEY UPDATE id = id;

SELECT CONCAT('第 4 步-A 完成：循环桶新增 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 4 步-B：插入一次性桶 9 行（category=2，押金 0）
--            ⚠️ category=2 是本脚本的核心决定，理由见头部说明。
--            ⚠️ 押金必须为 0：非桶装水在 OrderServiceImpl:361 会按
--               `deposit × qty` 收押金，填非 0 就会真的收钱。
-- -----------------------------------------------------------------------------
INSERT INTO product
    (owner_station_id, name, category, brand, spec, image_object_name, description,
     price, deposit, max_per_order, status, sort, ticket_enabled, ticket_price)
VALUES
    (NULL, '农夫山泉 饮用天然水 12L 一次性桶',   2, '农夫山泉', '12L',   '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；济南历城站在售「12L 一次性包装免押桶」；⚠️ 参考价按 19L 桶价与电商件装推算', 22.00, 0.00, 10, 1, 600, 1, 22.00),
    (NULL, '畅饮吧 饮用纯净水 15L 一次性桶',     2, '畅饮吧',   '15L',   '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；水立多章丘站在售 22 元/桶，明确标注「免押金」', 22.00, 0.00, 10, 1, 610, 1, 22.00),
    (NULL, '怡宝 纯净水 12.8L 一次性桶',         2, '怡宝',     '12.8L', '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；⚠️ 参考价取自同品牌 12.8L 中桶公开报价', 18.70, 0.00, 10, 1, 620, 1, 18.70),
    (NULL, '泉阳泉 长白山天然矿泉水 12L 一次性桶', 2, '泉阳泉', '12L',   '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；⚠️ 参考价取公开报价区间 22-30 元的中位', 26.00, 0.00, 10, 1, 630, 1, 26.00),
    (NULL, '泉阳泉 长白山天然矿泉水 15L 一次性桶', 2, '泉阳泉', '15L',   '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；⚠️ 参考价取自电商 15L 单桶报价', 38.00, 0.00, 10, 1, 640, 1, 38.00),
    (NULL, '润田翠 天然矿泉水 5L 一次性桶',      2, '润田翠',   '5L',    '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；济南配送商主营品牌；⚠️ 参考价由电商 5L×4 桶 55 元折算', 13.75, 0.00, 10, 1, 650, 1, 13.75),
    (NULL, '润田翠 天然水 12L 一次性桶',         2, '润田翠',   '12L',   '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；⚠️ 参考价由电商 12L×2 桶 36 元折算', 18.00, 0.00, 10, 1, 660, 1, 18.00),
    (NULL, '艺韵 泡茶专用水 3L 一次性桶',        2, '艺韵',     '3L',    '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；泡茶专用；济南享堂水站在售 25 元/4 桶，折算 6.25 元/桶', 6.25, 0.00, 10, 1, 670, 1, 6.25),
    (NULL, '阿尔卑斯 天然矿泉水 5L 一次性桶',    2, '阿尔卑斯', '5L',    '/assets/product/barrel-water.webp', '一次性桶（免押金、不回收）；济南配送商主营品牌；⚠️ 参考价由公开报价 5L×4 桶 62.9 元折算', 15.70, 0.00, 10, 1, 680, 1, 15.70)
ON DUPLICATE KEY UPDATE id = id;

SELECT CONCAT('第 4 步-B 完成：一次性桶新增 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 5 步：校验（只读）
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：货架全貌（应 52 行；桶装水 42 + 瓶装水 10）---' AS step;
SELECT id, brand, spec, category,
       CASE category WHEN 1 THEN '循环桶' WHEN 2 THEN '一次性桶' ELSE '其他' END AS kind,
       price, deposit, sort
FROM product WHERE owner_station_id IS NULL ORDER BY sort, id;

SELECT '--- 校验 B：★ 押金口径红线 —— 一次性桶(category=2)押金必须全为 0 ---' AS step;
SELECT id, name, category, deposit,
       CASE WHEN deposit = 0 THEN 'OK' ELSE '⚠️ 非桶装水会被真收押金！' END AS deposit_check,
       CASE WHEN image_object_name LIKE '/%' THEN 'OK' ELSE '⚠️ 非本地路径' END AS img_check
FROM product WHERE owner_station_id IS NULL AND category = 2 ORDER BY id;

SELECT '--- 校验 C：循环桶(category=1)押金分布（应只有 30 与 50）---' AS step;
SELECT deposit, COUNT(*) AS n FROM product
WHERE owner_station_id IS NULL AND category = 1 GROUP BY deposit ORDER BY deposit;

SELECT '--- 校验 D：行数与分类计数（通用库 52 / 桶装水 42 / 瓶装水 10）---' AS step;
SELECT COUNT(*) AS platform_rows FROM product WHERE owner_station_id IS NULL;
SELECT category, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL GROUP BY category ORDER BY category;

SELECT '--- 校验 E：无重复品（preset_uk 唯一性）---' AS step;
SELECT name, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL
GROUP BY name HAVING COUNT(*) > 1;

SELECT '--- 校验 F：站内自定义商品与库存未被波及 ---' AS step;
SELECT id, owner_station_id, name, price, deposit FROM product WHERE owner_station_id IS NOT NULL ORDER BY id;
SELECT id, station_id, product_id, quantity, enabled FROM inventory ORDER BY id;

SELECT '--- 校验 G：订单留痕未被回改（product_name_snapshot 应保持旧值）---' AS step;
SELECT COUNT(*) AS order_item_rows FROM order_item;
SELECT COUNT(*) AS orders_rows, IFNULL(SUM(total_amount), 0) AS orders_total FROM orders;

SELECT '--- 校验 H：brand 覆盖度（应 >= 30 个品牌）---' AS step;
SELECT COUNT(DISTINCT brand) AS brand_count FROM product WHERE owner_station_id IS NULL;

SELECT 'V53 完成：济南水站货架扩充到 52 行（新增 26 + 订正 1）；一次性桶按 category=2 归类，全链路无桶逻辑' AS note;

-- =============================================================================
-- 回滚用
-- =============================================================================
-- 【回滚 1】删除本轮新增的 26 行（按 brand+spec 精确定位，不误伤 v52 的 26 行）：
--
--   DELETE FROM product WHERE owner_station_id IS NULL AND (
--        (brand='云恬'         AND spec='18.9L')
--     OR (brand='一山一水'     AND spec IN ('18.9L'))
--     OR (brand='山下泉'       AND spec='18.9L')
--     OR (brand='天地矿泉'     AND spec='16.8L')
--     OR (brand='圣境甘泉'     AND spec='18.9L')
--     OR (brand='惬尔'         AND spec='18.9L')
--     OR (brand='艺韵'         AND spec IN ('18.9L','3L'))
--     OR (brand='雀巢'         AND spec='18.9L')
--     OR (brand='泉阳泉'       AND spec IN ('18.9L','12L','15L'))
--     OR (brand='恒大'         AND spec='17L')
--     OR (brand='雪峪长白山泉' AND spec='18.9L')
--     OR (brand='雪峪冰点'     AND spec='18.9L')
--     OR (brand='象牙山冰点'   AND spec='18.9L')
--     OR (brand='乐百氏'       AND spec='17.5L')
--     OR (brand='农夫山泉'     AND spec='12L')
--     OR (brand='畅饮吧'       AND spec='15L')
--     OR (brand='怡宝'         AND spec='12.8L')
--     OR (brand='润田翠'       AND spec IN ('5L','12L'))
--     OR (brand='阿尔卑斯'     AND spec='5L')
--   );
--   ⚠️ 执行前先确认这些 id 未被 inventory 引用：若已被站长选用，删除会破坏引用。
--
-- 【回滚 2】把「好山好水 15L 一次性桶」改回 v52 的循环桶归类：
--
--   UPDATE product
--   SET name='好山好水 天然水 15L 桶装水', category=1, deposit=50.00, sort=180,
--       description='中桶规格天然水'
--   WHERE owner_station_id IS NULL AND brand='好山好水' AND spec='15L'
--     AND name='好山好水 天然水 15L 一次性桶' AND category=2;
--   ⚠️ 但这会恢复「按循环桶收 50 元押金 + 生成桶异常单」的错误行为，仅用于脚本回退。
--
-- 【回滚 3】整库回退：从 backup/aquaflow_before_v53_<时间戳>.sql 恢复。
-- =============================================================================
