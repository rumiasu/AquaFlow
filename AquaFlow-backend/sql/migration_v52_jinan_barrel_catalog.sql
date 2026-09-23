-- =============================================================================
-- V52: 济南主流桶装水品类入库（平台通用商品库）
-- =============================================================================
-- 执行方式（必须指定库，脚本内不写 USE）：
--   export MYSQL_PWD=<密码>; D:/backend/MySQL/bin/mysql.exe -h127.0.0.1 -uroot --default-character-set=utf8mb4 \
--     aquaflow -e "source migration_v52_jinan_barrel_catalog.sql"
--   ⚠️ 不要用 `cmd /c "mysql ... < file.sql"`（本仓 Bash 沙箱会拦 cmd.exe）。
--   ⚠️ 不要用 PowerShell 管道（逐行处理会破坏多字节字符）。
-- 执行前先备份：mysqldump 到 backup/。
--
-- ⚠️ 编号说明：起草时本文件曾命名 v50，但 `migration_v50_enterprise_apply.sql`（企业身份申请）与
--    `migration_v51_station_enterprise_config.sql`（站级企业阈值）已被并行的另一条工作流占用，
--    故顺延为 **v52**。**内容与已执行的数据无任何差异**，仅文件编号与注释里的版本号不同。
--    （同类踩坑在 v38 一次：起草时以为可用 v33，实际 v33–v37 已被占用。）
--    教训：**动手写迁移前先 `ls migration_v*.sql` 看最大编号，不要凭 sql/README.md 的清单尾部猜** ——
--    清单更新有滞后，磁盘上的文件才是事实。
--
-- -----------------------------------------------------------------------------
-- 为什么需要它（2026-09-19）
--
--   平台通用库（product.owner_station_id IS NULL）此前只有 2 条种子商品，且是不带水种的
--   泛化名（「农夫山泉 19L 桶装水」「娃哈哈 18.9L 桶装水」）—— 站长在「选品」界面里
--   只有这两个选项，而济南市场上真实在卖的是「普利思天然泉水 / 泉娃饮用天然水 /
--   百脉泉山泉水 / 趵突泉纯净水…」这一整片本地货架。
--
--   本脚本按**济南实采报价**补齐主流品类，让站长能直接选到本地货架上真实存在的品。
--
-- -----------------------------------------------------------------------------
-- 口径（三条，都与既有约定对齐）
--
--   1. 只动**通用库**行：`owner_station_id IS NULL`。站内自定义商品（非空）一行不碰。
--   2. category 一律 = 1（桶装水）：只有 1 参与桶与押金逻辑
--      （`totalNeededBuckets` / `shortage = max(0, needed − 权益)` / 押金计算都只数它）。
--   3. 图一律用 v38 那张通用图 `/assets/product/barrel-water.webp`：
--      产品口径「不说循环桶，直接就是桶装水，业务逻辑是循环而已」——
--      水种差异体现在**商品名与价格**，不体现在**视觉**。
--
-- -----------------------------------------------------------------------------
-- 「卖法」落到哪几列（本仓现有的列，不新增字段）
--
--   · price        = 参考零售价（单桶送到家）
--   · deposit      = 空桶押金（循环桶才有；退桶可退）
--   · ticket_enabled / ticket_price = 是否可售水票 / 一张水票抵多少钱
--        语义正本 `StationProductVO.getEffectiveTicketPrice`：
--        站级水票价 → 通用库水票价 → 本站售价 → 参考价。
--        ⚠️ 本脚本把 ticket_price 设为**等于零售价**（票的面值 = 一桶水的价），
--        因为「买十赠一」是**赠**不是**降价** —— 它的归宿是 `ticket_package`
--        （站级档位：qty=11 / price=10 张的钱），不是这里。
--        （见 sql/README.md v36 条目：「定价结构不是促销引擎」。）
--
--   · 站级的起送量 / 配送范围 / 运费 / 楼层费 归 `station_delivery_config`（v35），
--     本脚本**不配**（那是每个水站自己的经营参数，不该由平台替他定）。
--
-- -----------------------------------------------------------------------------
-- 对既有 2 行的改动（这是本脚本唯一"改动存量"的部分，逐条说明理由）
--
--   A. **改名补上水种**：`农夫山泉 19L 桶装水` → `农夫山泉 饮用天然水 19L 桶装水`；
--      `娃哈哈 18.9L 桶装水` → `娃哈哈 饮用纯净水 18.9L 桶装水`。
--      · 必要性：新增的 24 行全部带水种（同品牌同规格不同水种是不同的品、价格差一倍），
--        这 2 行不带水种则整个货架口径不一致，且与「娃哈哈 天然泉水 16.8L」这类行无法区分。
--      · **安全性（已逐项核实，不是推断）**：
--        ① 历史订单**不读活名** —— `order_item` 有 `product_name_snapshot` /
--           `brand_snapshot` / `spec_snapshot` 三个快照列，20 条 order_item 全部已快照
--           （实测 `select product_name_snapshot` 全部有值）；`OrderMapper` /
--           `DashboardMapper` / `GrossProfitMapper` 的下单、看板、毛利三处展示**全走快照**。
--        ② 引用**按 id 不按名** —— order_item 20 行 / inventory 4 行 /
--           customer_barrel_asset 2 行都存 product_id，改名不影响。
--        ③ 唯一键安全 —— `preset_uk = name|brand|spec`，新名不与任何新增行重名（见下）。
--      · 唯一可见副作用：`CustomerMapper.listFavoriteProducts`（站长端客户画像
--        「常用商品 TOP3」）用的是 `ifnull(p.name, oi.product_name_snapshot)` ——
--        **活名优先**，所以那处标签会跟着变成新名。这是文案级影响，且新名更准确。
--   B. **押金 30 → 50**：济南实采两个独立来源均指向 50
--      （水立多济南站：「农夫山泉 19升 无空桶需要付押金50元1个」「娃哈哈饮用纯净水18.9L
--      无空桶需要付押金 50元」；爱企查：「本地品牌约40元，大品牌约50元」）。
--      原种子值 30 无来源，且会让「农夫山泉 押30 / 怡宝 押50」在同一张货架上自相矛盾。
--      ⚠️ 这是**会真的改变计费**的改动：station 1 的 `inventory.deposit_price` 为 NULL，
--      按 `PriceUtil.calcDeposit` 会回落到 product.deposit —— 即客户首单缺桶押金由
--      30/桶 变 50/桶。**这是本脚本唯一影响真实金额的地方**，回滚 SQL 见文末。
--   C. 两行的 `ticket_enabled` 由 0 置 1、`ticket_price` 由 0 置 = 零售价、`sort` 规整。
--
-- -----------------------------------------------------------------------------
-- 幂等
--   · UPDATE 全部带「旧值」条件（旧名 / 旧押金 / 旧 ticket 开关）→ 二次执行影响 0 行。
--   · INSERT 用 `ON DUPLICATE KEY UPDATE id = id`（唯一键 `preset_uk` 撞上就空转）
--     → 二次执行新增 0 行。**不用 INSERT IGNORE**：那会把 NOT NULL 之类的真错误也吞成警告。
--
-- 回滚
--   · 删掉新增的 24 行：
--       DELETE FROM product
--       WHERE owner_station_id IS NULL
--         AND name IN ('普利思 天然泉水 18.9L 桶装水', ... 见文末完整清单 ...);
--   · 恢复既有 2 行：
--       UPDATE product SET name='农夫山泉 19L 桶装水', deposit=30.00,
--              ticket_enabled=0, ticket_price=0.00, sort=1
--       WHERE owner_station_id IS NULL AND brand='农夫山泉' AND spec='19L';
--       UPDATE product SET name='娃哈哈 18.9L 桶装水', deposit=30.00,
--              ticket_enabled=0, ticket_price=0.00, sort=2
--       WHERE owner_station_id IS NULL AND brand='娃哈哈' AND spec='18.9L';
--   ⚠️ 若这 24 行已被站长「选用」（inventory 出现引用）或已产生订单，
--      删除会破坏外键语义 —— 回滚前先查 `select count(*) from inventory where product_id in (...)`。
--
-- -----------------------------------------------------------------------------
-- 价格来源（2026-09 实采，均为济南本地水站报价，非全国均价）
--
--   | 品牌 | 水种 | 规格 | 价格 | 来源 |
--   |---|---|---|---|---|
--   | 普利思 | 天然泉水 | 18.9L | 11–12 | 列表网 凤凰山路站 / 黄台魏家庄站 |
--   | 普利思 | 天然矿泉水 | 18.9L | 15–16 | 同上 |
--   | 普利思 | 纯净水 | 18.9L | 8–12 | 同上 + 高新区站 |
--   | 泉娃 | 饮用天然水 | 18.5L | 12 | 列表网 高新区站 |
--   | 泉娃 | 天然矿泉水 | 18.5L | 15 | 同上 |
--   | 百脉泉 | 天然矿泉水 | 18.9L | 13–14 | 列表网 甸柳新村站 / 于先生站 |
--   | 百脉泉 | 山泉水 | 18.9L | 9 | 列表网 甸柳新村站 |
--   | 趵突泉 | 纯净水 | 18.9L | 10 | 列表网 商河站 |
--   | 趵突泉 | 天然矿泉水 | 18.9L | 13 | 同上 |
--   | 百圣泉 | 山泉水 | 18.9L | 8 | 列表网 金凤苑/历下站 |
--   | 冰露 | 纯净水 | 18.9L | 11 | 同上 |
--   | 涵露 | 饮用天然水 | 18L | 10 | 列表网 高新区站 |
--   | 七星台 | 纯净水 | 18.9L | 14 | 水立多 章丘站 |
--   | 泉城茗水 | 天然泉水 | 17L | 13 | 水立多 济南站 |
--   | 爱茶说 | 泡茶山泉水 | 17.5L | 15 | 同上 |
--   | 泰山甘泉 | 饮用天然水 | 18.9L | 20 | 水立多 章丘站 |
--   | 好山好水 | 天然水 | 15L | 24 | 同上 |
--   | 农夫山泉 | 饮用天然水 | 19L | 22（存量） | 水立多 / 电商 21–24 |
--   | 娃哈哈 | 饮用纯净水 | 18.9L | 20（存量） | 水立多 章丘 22 / 区间 16–22 |
--   | 娃哈哈 | 天然泉水 | 16.8L | 23 | 水立多 章丘站 27 / 济南站 23 |
--   | 怡宝 | 纯净水 | 18.9L | 18 | 区间参考（16–26），济南未见单点报价 |
--   | 景田百岁山 | 天然矿泉水 | 18.9L | 18 | 列表网 享堂水站（济南） |
--   | 乐百氏 | 天然泉水 | 18.9L | 28 | 水立多 章丘站 |
--   | 崂山 | 天然矿泉水 | 19L | 20 | 列表网 山东（青岛平度站，同省同价带） |
--   | 崂山 | 山泉水 | 19L | 17 | 同上 |
--
--   取价原则：区间取**众数/中位**，不取最低价（最低价多为促销或大宗价，不代表货架价）。
--
-- -----------------------------------------------------------------------------
-- ⚠️ 本脚本**刻意不做**的事（附理由，避免后人当成遗漏）
--
--   · **不加「一次性桶」**（农夫山泉 12L/19L 一次性免押、泉阳泉 15L、泉阳泉/农夫山泉
--     免押金箱装）。原因：本仓**没有任何列能表达「一次性」** —— 桶与押金逻辑的判据
--     只有 `category = 1`。把一次性桶按 category=1 建行，会让它：
--       ① 计入 `totalNeededBuckets` → 向客户收押金（虽然 deposit=0 可绕过）；
--       ② 进 `customer_barrel_in_transit` → 送达后要求回桶 → **凭空生成桶异常单**。
--     要支持它需先加一个「复用方式」维度（如 `reuse_mode`：循环 / 一次性），
--     并同改 `OrderServiceImpl` / `BarrelLedgerService` / 回桶核对三处。属独立改动。
--   · **不配站级水票档位**（买十赠一 = 11 张 / 10 张的钱）：归 `ticket_package`（v36），
--     是站级经营参数，且每站政策不同。本脚本只把 product 层的「可售水票」开关打开。
--   · **不配起送量 / 配送范围 / 运费 / 楼层费**：归 `station_delivery_config`（v35）。
--   · **不动 `inventory`**：站长选不选、卖多少钱、押金多少，是站长的事。
--     通用库只提供**参考价**，站级 `inventory.sale_price/deposit_price` 优先。
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
SELECT '--- 执行前：通用库商品 ---' AS step;
SELECT id, name, brand, spec, category, price, deposit, ticket_enabled, sort
FROM product
WHERE owner_station_id IS NULL
ORDER BY id;

SELECT '--- 执行前：站内自定义商品（本脚本应完全不碰）---' AS step;
SELECT id, owner_station_id, name FROM product WHERE owner_station_id IS NOT NULL ORDER BY id;

-- -----------------------------------------------------------------------------
-- 第 3 步：规范化既有 2 行（改名补水种 + 押金订正 + 水票开关 + 排序）
--         全部带旧值条件 → 二次执行影响 0 行
-- -----------------------------------------------------------------------------
UPDATE product
SET name           = '农夫山泉 饮用天然水 19L 桶装水',
    deposit        = 50.00,
    ticket_enabled = 1,
    ticket_price   = 22.00,
    sort           = 210,
    description    = '全国品牌；产地承德雾灵山'
WHERE owner_station_id IS NULL
  AND brand = '农夫山泉' AND spec = '19L'
  AND name = '农夫山泉 19L 桶装水';

UPDATE product
SET name           = '娃哈哈 饮用纯净水 18.9L 桶装水',
    deposit        = 50.00,
    ticket_enabled = 1,
    ticket_price   = 20.00,
    sort           = 220,
    description    = '全国品牌；空桶押金 50 元'
WHERE owner_station_id IS NULL
  AND brand = '娃哈哈' AND spec = '18.9L'
  AND name = '娃哈哈 18.9L 桶装水';

-- -----------------------------------------------------------------------------
-- 第 4 步：插入济南主要桶装水（24 行）
--          ON DUPLICATE KEY UPDATE id = id → 撞 preset_uk 就空转（幂等）
-- -----------------------------------------------------------------------------
INSERT INTO product
    (owner_station_id, name, category, brand, spec, image_object_name, description,
     price, deposit, max_per_order, status, sort, ticket_enabled, ticket_price)
VALUES
    -- ===== 本地主力（济南本地品牌 / 山东水源）=====
    (NULL, '普利思 天然泉水 18.9L 桶装水',  1, '普利思',   '18.9L', '/assets/product/barrel-water.webp', '济南本地龙头；水票买十赠一，空桶可互换',       12.00, 50.00, 10, 1,  10, 1, 12.00),
    (NULL, '普利思 天然矿泉水 18.9L 桶装水', 1, '普利思',   '18.9L', '/assets/product/barrel-water.webp', '济南本地龙头；含锶型矿泉水，水票买十赠一',     16.00, 50.00, 10, 1,  20, 1, 16.00),
    (NULL, '普利思 纯净水 18.9L 桶装水',    1, '普利思',   '18.9L', '/assets/product/barrel-water.webp', '济南本地龙头；水票买十赠一',                   12.00, 50.00, 10, 1,  30, 1, 12.00),
    (NULL, '泉娃 饮用天然水 18.5L 桶装水',  1, '泉娃',     '18.5L', '/assets/product/barrel-water.webp', '济南本地主力；水票买十赠一',                   12.00, 50.00, 10, 1,  40, 1, 12.00),
    (NULL, '泉娃 天然矿泉水 18.5L 桶装水',  1, '泉娃',     '18.5L', '/assets/product/barrel-water.webp', '精品弱碱型矿泉水；水票买十赠一',               15.00, 50.00, 10, 1,  50, 1, 15.00),
    (NULL, '百脉泉 天然矿泉水 18.9L 桶装水', 1, '百脉泉',   '18.9L', '/assets/product/barrel-water.webp', '章丘鸣羊山水源；空桶可与普利思/泉娃互换',       13.00, 50.00, 10, 1,  60, 1, 13.00),
    (NULL, '百脉泉 山泉水 18.9L 桶装水',    1, '百脉泉',   '18.9L', '/assets/product/barrel-water.webp', '章丘鸣羊山水源；水票买十送一',                  9.00, 50.00, 10, 1,  70, 1,  9.00),
    (NULL, '百脉泉 纯净水 18.9L 桶装水',    1, '百脉泉',   '18.9L', '/assets/product/barrel-water.webp', '章丘鸣羊山水源；水票买十送一',                 10.00, 50.00, 10, 1,  80, 1, 10.00),
    (NULL, '趵突泉 天然矿泉水 18.9L 桶装水', 1, '趵突泉',   '18.9L', '/assets/product/barrel-water.webp', '济南地标品牌',                                 13.00, 50.00, 10, 1,  90, 1, 13.00),
    (NULL, '趵突泉 纯净水 18.9L 桶装水',    1, '趵突泉',   '18.9L', '/assets/product/barrel-water.webp', '济南地标品牌',                                 10.00, 50.00, 10, 1, 100, 1, 10.00),
    (NULL, '百圣泉 山泉水 18.9L 桶装水',    1, '百圣泉',   '18.9L', '/assets/product/barrel-water.webp', '本地低价位；空桶押金 30 元',                    8.00, 30.00, 10, 1, 110, 1,  8.00),
    (NULL, '冰露 纯净水 18.9L 桶装水',      1, '冰露',     '18.9L', '/assets/product/barrel-water.webp', '可口可乐旗下；空桶押金 50 元',                 11.00, 50.00, 10, 1, 120, 1, 11.00),
    (NULL, '涵露 饮用天然水 18L 桶装水',    1, '涵露',     '18L',   '/assets/product/barrel-water.webp', '买水票可免费提供饮水机',                       10.00, 50.00, 10, 1, 130, 1, 10.00),
    (NULL, '七星台 纯净水 18.9L 桶装水',    1, '七星台',   '18.9L', '/assets/product/barrel-water.webp', '章丘七星台水源',                               14.00, 50.00, 10, 1, 140, 1, 14.00),
    (NULL, '泉城茗水 天然泉水 17L 桶装水',  1, '泉城茗水', '17L',   '/assets/product/barrel-water.webp', '中桶规格，适合家庭',                           13.00, 50.00, 10, 1, 150, 1, 13.00),
    (NULL, '爱茶说 泡茶山泉水 17.5L 桶装水', 1, '爱茶说',  '17.5L', '/assets/product/barrel-water.webp', '泡茶专用山泉水',                               15.00, 50.00, 10, 1, 160, 1, 15.00),
    (NULL, '泰山甘泉 饮用天然水 18.9L 桶装水', 1, '泰山甘泉', '18.9L', '/assets/product/barrel-water.webp', '泰山水源天然水',                             20.00, 50.00, 10, 1, 170, 1, 20.00),
    (NULL, '好山好水 天然水 15L 桶装水',    1, '好山好水', '15L',   '/assets/product/barrel-water.webp', '中桶规格天然水',                               24.00, 50.00, 10, 1, 180, 1, 24.00),
    -- ===== 全国品牌（济南在售）=====
    (NULL, '娃哈哈 天然泉水 16.8L 桶装水',  1, '娃哈哈',   '16.8L', '/assets/product/barrel-water.webp', '全国品牌；16.8L 中桶',                         23.00, 50.00, 10, 1, 230, 1, 23.00),
    (NULL, '怡宝 纯净水 18.9L 桶装水',      1, '怡宝',     '18.9L', '/assets/product/barrel-water.webp', '全国品牌；空桶押金 50 元',                     18.00, 50.00, 10, 1, 240, 1, 18.00),
    (NULL, '景田百岁山 天然矿泉水 18.9L 桶装水', 1, '景田百岁山', '18.9L', '/assets/product/barrel-water.webp', '偏硅酸型天然矿泉水',                       18.00, 50.00, 10, 1, 250, 1, 18.00),
    (NULL, '乐百氏 天然泉水 18.9L 桶装水',  1, '乐百氏',   '18.9L', '/assets/product/barrel-water.webp', '全国品牌',                                     28.00, 50.00, 10, 1, 260, 1, 28.00),
    (NULL, '崂山 天然矿泉水 19L 桶装水',    1, '崂山',     '19L',   '/assets/product/barrel-water.webp', '山东本地水源',                                 20.00, 50.00, 10, 1, 270, 1, 20.00),
    (NULL, '崂山 山泉水 19L 桶装水',        1, '崂山',     '19L',   '/assets/product/barrel-water.webp', '山东本地水源',                                 17.00, 50.00, 10, 1, 280, 1, 17.00)
ON DUPLICATE KEY UPDATE id = id;

SELECT CONCAT('第 4 步完成：新增 ', ROW_COUNT(), ' 行（幂等重跑时为 0）') AS note;

-- -----------------------------------------------------------------------------
-- 第 5 步：校验
-- -----------------------------------------------------------------------------
SELECT '--- 校验 A：通用库货架全貌（应为 26 行，全部 category=1 且有图）---' AS step;
SELECT id, brand, spec, name, price, deposit, ticket_enabled, ticket_price, sort,
       CASE WHEN category = 1 THEN 'OK'
            ELSE CONCAT('⚠️ category=', category) END AS cat_check,
       CASE WHEN image_object_name LIKE '/%' THEN 'OK'
            ELSE '⚠️ 非本地路径' END AS img_check
FROM product
WHERE owner_station_id IS NULL
ORDER BY sort, id;

SELECT '--- 校验 B：站内自定义商品未被波及（应保持原值/空）---' AS step;
SELECT id, owner_station_id, name, price, deposit FROM product WHERE owner_station_id IS NOT NULL ORDER BY id;

SELECT '--- 校验 C：行数与分类计数（通用库 26 / 桶装水 26）---' AS step;
SELECT COUNT(*) AS platform_rows FROM product WHERE owner_station_id IS NULL;
SELECT category, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL GROUP BY category;

SELECT '--- 校验 D：无重复品（同 品牌+规格+水种 只应 1 行）---' AS step;
SELECT name, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL
GROUP BY name HAVING COUNT(*) > 1;

SELECT '--- 校验 E：preset_uk 无冲突痕迹 + 押金分布 ---' AS step;
SELECT deposit, COUNT(*) AS n FROM product WHERE owner_station_id IS NULL GROUP BY deposit ORDER BY deposit;
SELECT COUNT(*) AS preset_uk_all_nonnull FROM product WHERE owner_station_id IS NULL AND preset_uk IS NOT NULL;

SELECT 'V52 完成：济南主流桶装水已入平台通用库（26 行，含 2 行存量改名+押金订正）' AS note;

-- =============================================================================
-- 回滚用：新增行完整清单（24 行）
--   普利思 天然泉水 18.9L 桶装水 / 普利思 天然矿泉水 18.9L 桶装水 / 普利思 纯净水 18.9L 桶装水
--   泉娃 饮用天然水 18.5L 桶装水 / 泉娃 天然矿泉水 18.5L 桶装水
--   百脉泉 天然矿泉水 18.9L 桶装水 / 百脉泉 山泉水 18.9L 桶装水 / 百脉泉 纯净水 18.9L 桶装水
--   趵突泉 天然矿泉水 18.9L 桶装水 / 趵突泉 纯净水 18.9L 桶装水
--   百圣泉 山泉水 18.9L 桶装水 / 冰露 纯净水 18.9L 桶装水 / 涵露 饮用天然水 18L 桶装水
--   七星台 纯净水 18.9L 桶装水 / 泉城茗水 天然泉水 17L 桶装水 / 爱茶说 泡茶山泉水 17.5L 桶装水
--   泰山甘泉 饮用天然水 18.9L 桶装水 / 好山好水 天然水 15L 桶装水
--   娃哈哈 天然泉水 16.8L 桶装水 / 怡宝 纯净水 18.9L 桶装水 / 景田百岁山 天然矿泉水 18.9L 桶装水
--   乐百氏 天然泉水 18.9L 桶装水 / 崂山 天然矿泉水 19L 桶装水 / 崂山 山泉水 19L 桶装水
--
--   DELETE FROM product
--   WHERE owner_station_id IS NULL
--     AND id NOT IN (SELECT id FROM (SELECT MIN(id) AS id FROM product
--                    WHERE owner_station_id IS NULL AND brand IN ('农夫山泉','娃哈哈')
--                      AND spec IN ('19L','18.9L')) t);
--   ⚠️ 更稳的做法：按上面清单显式列 name，并先查 inventory 有无引用。
-- =============================================================================
