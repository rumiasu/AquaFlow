CREATE TABLE IF NOT EXISTS product_image_library (
  image_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '图库稳定键',
  label VARCHAR(120) NOT NULL COMMENT '站长选图名称',
  brand VARCHAR(80) DEFAULT NULL,
  spec VARCHAR(80) DEFAULT NULL COMMENT '含包装数；规格未核实时为空或只写已核对部分',
  image_ref VARCHAR(500) NOT NULL COMMENT '已发布：包内路径或持久图片引用；参考：素材包相对路径',
  source_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_url VARCHAR(512) DEFAULT NULL COMMENT '原始出处页面；用户提供的文件留空',
  source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  asset_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  review_note VARCHAR(255) DEFAULT NULL COMMENT '规格、包装或使用范围的复核说明',
  published TINYINT NOT NULL DEFAULT 0,
  sort_order SMALLINT NOT NULL DEFAULT 0,
  PRIMARY KEY (image_key),
  KEY idx_product_image_published (published, sort_order, image_key),
  CONSTRAINT chk_product_image_published CHECK (published IN (0,1)),
  CONSTRAINT chk_product_image_source CHECK (source_kind IN ('internal','manufacturer','merchant','user')),
  CONSTRAINT chk_product_image_reference CHECK (published = 0 OR image_ref NOT LIKE 'catalog-image-assets/%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台商品图库；参考素材不向站长或顾客展示';

-- 初次部署保持旧预设可用，不依赖小程序先更新。历史引用不改。
INSERT INTO product_image_library (image_key,label,brand,image_ref,source_kind,source_url,published,sort_order) VALUES
  ('barrel-neutral-template','通用桶装水示意图',NULL,'/assets/product/barrel-neutral-template.webp','internal',NULL,1,10),
  ('pulisi-purified-photo','普利思 · 纯净水','普利思','catalog-sample-20261023-pulisi-purified-87bf702c165d.png','manufacturer','https://www.pulisi.com/product/128.html',1,20),
  ('pulisi-spring-photo','普利思 · 天然泉水','普利思','catalog-sample-20261023-pulisi-spring-f576447ab103.png','manufacturer','https://www.pulisi.com/product/130.html',1,30),
  ('pulisi-mineral-photo','普利思 · 天然矿泉水','普利思','catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png','manufacturer','https://www.pulisi.com/product/129.html',1,40)
ON DUPLICATE KEY UPDATE image_key = product_image_library.image_key;
