-- 可选公共目录初始化：普利思三种18.5L。不是结构迁移，不由init.sql自动运行。
-- 必须先明确目标库并备份，在同一mysql连接显式设置下列六个会话参数：
-- @af_pulisi_purified_price / @af_pulisi_spring_price / @af_pulisi_mineral_price
-- @af_pulisi_purified_deposit / @af_pulisi_spring_deposit / @af_pulisi_mineral_deposit
-- 金额最多8位整数、2位小数。缺参不是0，显式售价0不改既有合法零值语义；
-- 本三款均为桶装水，押金沿现有商品创建约束须大于0。不代填市场价格。
-- 只补缺失公共身份；同款（含已下架和已停售）原样保留，不更新任何存量行。
-- 不创建inventory，不选用、不上本站货架，不改本站价格、押金、库存或历史凭据。
-- 复用现有uk_product_preset；并发首次初始化若唯一键竞争报错，核实后重跑即可。
-- 不用INSERT IGNORE / ON DUPLICATE KEY UPDATE，不吞真实错误或触发存量更新时间。
-- 不使用mysql --force；即使客户端错误地继续，INSERT仍有独立有效输入/结构条件。
-- 资料及2019年18.5L证据边界：docs/design/14-商品图片库与常见水种清单.md §5。
-- 厂商资料入口：https://www.pulisi.com/product/{128,130,129}.html
-- 图片为已验原创无品牌中性示意图，不是品牌包装照。实际规格需按实物核对；
-- 站长若需编辑身份，可沿原页面明确另存本站商品，不改公共SKU。

SET @af_catalog_seed_valid = IFNULL(
    REGEXP_LIKE(CAST(@af_pulisi_purified_price AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND REGEXP_LIKE(CAST(@af_pulisi_spring_price AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND REGEXP_LIKE(CAST(@af_pulisi_mineral_price AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND REGEXP_LIKE(CAST(@af_pulisi_purified_deposit AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND REGEXP_LIKE(CAST(@af_pulisi_spring_deposit AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND REGEXP_LIKE(CAST(@af_pulisi_mineral_deposit AS CHAR), '^[0-9]{1,8}([.][0-9]{1,2})?$')
    AND CAST(@af_pulisi_purified_deposit AS DECIMAL(10,2)) > 0
    AND CAST(@af_pulisi_spring_deposit AS DECIMAL(10,2)) > 0
    AND CAST(@af_pulisi_mineral_deposit AS DECIMAL(10,2)) > 0, 0);
SET @af_catalog_seed_schema = (
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('product','inventory','orders')) = 3
    AND EXISTS(SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product'
                 AND INDEX_NAME = 'uk_product_preset' AND NON_UNIQUE = 0
                 AND COLUMN_NAME = 'preset_uk'));
SET @af_catalog_seed_check = IF(@af_catalog_seed_valid AND @af_catalog_seed_schema,
    'SELECT ''AQUAFLOW_CATALOG_SEED_INPUTS_OK'' AS precheck',
    'SELECT * FROM __AQUAFLOW_CATALOG_SEED_MISSING_OR_INVALID_AMOUNTS_OR_SCHEMA__');
PREPARE af_catalog_seed_precheck FROM @af_catalog_seed_check;
EXECUTE af_catalog_seed_precheck;
DEALLOCATE PREPARE af_catalog_seed_precheck;

-- 单条INSERT保证三款新增原子提交；按模板同品牌/水种/容量的精确身份匹配，
-- 空白与L大小写归一化，不将18.9L、异站私有品或其它水种猜成同款。
INSERT INTO product
    (owner_station_id, name, category, brand, spec, image_object_name, description,
     price, deposit, status, sort, ticket_enabled, ticket_price)
SELECT NULL, seed.name, 1, '普利思', '18.5L', '/assets/product/barrel-neutral-template.webp',
       '商品示意图，包装以实物为准。18.5L为历史资料起点，请核对实物标签；本站售价、押金、库存及上架由本站确认。',
       CAST(seed.price AS DECIMAL(10,2)), CAST(seed.deposit AS DECIMAL(10,2)), 1, 0, 0, 0
FROM (
    SELECT '普利思 饮用纯净水' AS name, '普利思纯净水' AS alias,
           @af_pulisi_purified_price AS price, @af_pulisi_purified_deposit AS deposit
    UNION ALL
    SELECT '普利思 饮用天然泉水', '普利思天然泉水', @af_pulisi_spring_price, @af_pulisi_spring_deposit
    UNION ALL
    SELECT '普利思 饮用天然矿泉水', '普利思天然矿泉水', @af_pulisi_mineral_price, @af_pulisi_mineral_deposit
) AS seed
WHERE @af_catalog_seed_valid = 1 AND @af_catalog_seed_schema = 1
  AND NOT EXISTS (
      SELECT 1 FROM product p
      WHERE p.owner_station_id IS NULL AND p.category = 1
        AND REGEXP_REPLACE(COALESCE(p.brand,''), '[[:space:]]', '') = '普利思'
        AND LOWER(REGEXP_REPLACE(COALESCE(p.spec,''), '[[:space:]]', '')) = '18.5l'
        AND LOWER(REGEXP_REPLACE(p.name, '[[:space:]]', '')) IN (
            REPLACE(seed.name, ' ', ''), seed.alias,
            CONCAT(REPLACE(seed.name, ' ', ''), '18.5l'), CONCAT(seed.alias, '18.5l'),
            CONCAT(REPLACE(seed.name, ' ', ''), '18.5l桶装水'), CONCAT(seed.alias, '18.5l桶装水'))
  );
SELECT ROW_COUNT() AS inserted_public_products;
SELECT IF(@af_catalog_seed_valid = 1 AND @af_catalog_seed_schema = 1,
          'AQUAFLOW_PUBLIC_CATALOG_SEED_OK', 'AQUAFLOW_PUBLIC_CATALOG_SEED_REFUSED') AS completion;
