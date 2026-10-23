package com.example.aquaflow.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台选图面板的资源映射：包内示意图或已核对的COS品牌原图。
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

    private static final Map<String, String> PRESET_TO_PATH;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("barrel-neutral-template", DIR + "barrel-neutral-template.webp");
        // 已上传并核对原标识、大小和读取效果的品牌原图；不定义容量或金额。
        m.put("pulisi-purified-photo", "catalog-sample-20261023-pulisi-purified-87bf702c165d.png");
        m.put("pulisi-spring-photo", "catalog-sample-20261023-pulisi-spring-f576447ab103.png");
        m.put("pulisi-mineral-photo", "catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png");
        PRESET_TO_PATH = Collections.unmodifiableMap(m);
    }

    /**
     * 预设图 key → 包内路径或COS对象键；后者须经ProductImageResolver签名后展示。
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
}
