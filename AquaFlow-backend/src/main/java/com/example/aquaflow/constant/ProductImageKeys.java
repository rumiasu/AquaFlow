package com.example.aquaflow.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台选图面板的包内资源映射，两个小程序必须保留相同文件。
 *
 * <p>2026-10-22：沿用已授权的中性桶装水示意图，不恢复早期撤下的自制图，
 * 不登记未经核实的品牌实拍图。站长及顾客图片旁须标明商品示意图，包装以实物为准。
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
        PRESET_TO_PATH = Collections.unmodifiableMap(m);
    }

    /**
     * 预设图 key → 小程序包内资源路径。
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
