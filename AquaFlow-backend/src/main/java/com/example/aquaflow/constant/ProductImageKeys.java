package com.example.aquaflow.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台预设商品图 —— key 与小程序包内资源路径的唯一映射。
 *
 * <p><b>口径：</b>预设图存放在<b>两个小程序的包内</b>（{@code assets/product/}），
 * 不依赖 COS、不会过期、无网络请求。后端 {@code product.image_object_name} 存<b>路径</b>
 * （形如 {@code /assets/product/barrel-water.webp}），由
 * {@link com.example.aquaflow.util.ProductImageResolver#resolve} 原样下发。</p>
 *
 * <p><b>为什么 key 与路径都要有：</b>key 是稳定标识（未来可能改名或换存储），
 * 路径是当前实现细节。站长端选图时用 key，后端翻译成路径下发。</p>
 *
 * <p>⚠️ 新增/删除预设图时，<b>必须同步</b>：① 本表；② {@code miniapp-user/assets/product/}；
 * ③ {@code miniapp-delivery/assets/product/}。两端包内各存一份是微信小程序的固有限制
 * （包内资源不能跨小程序共享），文件本身很小（单张约 7 KB），重复可以接受。</p>
 */
public final class ProductImageKeys {

    private ProductImageKeys() {
    }

    /** 通用桶装水（无品牌、无标签）：用于循环桶装水这一通用品类 */
    public static final String BARREL_WATER = "barrel-water";

    /** 饮用纯净水桶 */
    public static final String BARREL_PURIFIED = "barrel-purified";

    /** 本地资源存放目录（两个小程序包内保持一致） */
    private static final String DIR = "/assets/product/";

    private static final Map<String, String> PRESET_TO_PATH;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(BARREL_WATER, DIR + "barrel-water.webp");
        m.put(BARREL_PURIFIED, DIR + "barrel-purified.webp");
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
