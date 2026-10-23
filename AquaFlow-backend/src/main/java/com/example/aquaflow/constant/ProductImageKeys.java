package com.example.aquaflow.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台选图面板的资源映射：包内示意图或已核对的品牌原图。
 *
 * <p>保留已授权的中性示意图及2026-10-23确认上传的三张原图，不恢复旧AI改标图。
 * 中性图须标明商品示意图；品牌资料图也不代表当前容量、官方价格或桶兼容关系。
 * 商品身份、规格、价格及桶兼容规则仍由原业务资料决定。</p>
 */
public final class ProductImageKeys {

    private ProductImageKeys() {
    }

    /** 本地资源存放目录（两个小程序包内保持一致） */
    private static final String DIR = "/assets/product/";

    private static final String PULISI_PURIFIED = "catalog-sample-20261023-pulisi-purified-87bf702c165d.png";
    private static final String PULISI_SPRING = "catalog-sample-20261023-pulisi-spring-f576447ab103.png";
    private static final String PULISI_MINERAL = "catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png";

    private static final Map<String, String> PRESET_TO_PATH;
    private static final Map<String, String> VERIFIED_PUBLIC_ORIGINALS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("barrel-neutral-template", DIR + "barrel-neutral-template.webp");
        // 已上传并核对原标识、大小和读取效果的品牌原图；不定义容量或金额。
        m.put("pulisi-purified-photo", PULISI_PURIFIED);
        m.put("pulisi-spring-photo", PULISI_SPRING);
        m.put("pulisi-mineral-photo", PULISI_MINERAL);
        PRESET_TO_PATH = Collections.unmodifiableMap(m);

        // 官网对应产品页 128 / 130 / 129 的原图，精确匹配已上传的三张品牌资料图。
        // 不按商品名、文件后缀或相似对象键猜图；对象键仍保留在商品资料中。
        Map<String, String> originals = new LinkedHashMap<>();
        originals.put(PULISI_PURIFIED, "https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png");
        originals.put(PULISI_SPRING, "https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png");
        originals.put(PULISI_MINERAL, "https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png");
        VERIFIED_PUBLIC_ORIGINALS = Collections.unmodifiableMap(originals);
    }

    /**
     * 预设图 key → 包内路径或COS对象键；后者须经ProductImageResolver解析后展示。
     *
     * @param presetKey 预设 key，可为 null
     * @return 资源路径；key 为空或未知时返回 {@code null}（调用方按"无图"处理）
     */
    public static String toPath(String presetKey) {
        if (presetKey == null || presetKey.isEmpty()) {
            return null;
        }
        return PRESET_TO_PATH.get(presetKey);
    }

    /** 全部预设 key（下发给站长端选图面板） */
    public static Map<String, String> all() {
        return PRESET_TO_PATH;
    }

    /** 仅精确登记的品牌资料对象键可直接展示官网永久原图；未知上传仍走原COS链路。 */
    public static String verifiedPublicOriginal(String objectName) {
        return VERIFIED_PUBLIC_ORIGINALS.get(objectName);
    }

    /** 保留旧包内图映射供兼容核对；解析器已优先使用永久原图。历史pure/mineral文件名不能按字面猜。 */
    public static String localFallback(String objectName) {
        if (PULISI_PURIFIED.equals(objectName)) {
            return DIR + "pulisi-mineral.webp"; // 红标：饮用纯净水
        }
        if (PULISI_SPRING.equals(objectName)) {
            return DIR + "pulisi-spring.webp";
        }
        if (PULISI_MINERAL.equals(objectName)) {
            return DIR + "pulisi-pure.webp"; // 蓝标：饮用天然矿泉水
        }
        return null;
    }
}
