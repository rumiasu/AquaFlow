package com.example.aquaflow.constant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台预设商品图 —— key 与小程序包内资源路径的唯一映射。
 *
 * <p><b>当前为空（2026-09-20）。</b>产品口径已明确：<b>只用品牌官网实拍图</b>，
 * 不使用平台自行生成 / 加工的图。原先的两张自制图（{@code barrel-water} / {@code barrel-purified}）
 * 已连同两端包内文件一起移除；引用它们的 47 行商品**均已下架**，
 * 其 {@code image_object_name} 也已置空（见 {@code migration_v57_clear_selfmade_images.sql}）。</p>
 *
 * <p><b>将来要恢复这个面板</b>：把实拍图放进 {@code assets/product/}、在静态块里登记
 * key → 路径即可，<b>无需改任何调用方</b> —— {@code GET /api/manager/catalog/preset-images}
 * 直接读 {@link #all()}，前端空列表时已显示「暂无预设图」。</p>
 *
 * <p>⚠️ 新增图必须<b>同步三处</b>：本表 + {@code miniapp-user/assets/product/} +
 * {@code miniapp-delivery/assets/product/}（微信包内资源不能跨小程序共享）。</p>
 *
 * <p>⚠️ 别与「品牌图」混淆：品牌图由商品行<b>直接存路径</b>（如
 * {@code /assets/product/pulisi-pure.webp}），<b>不经过 key 体系</b>；
 * 本表只服务「站长自选图」面板。</p>
 */
public final class ProductImageKeys {

    private ProductImageKeys() {
    }

    /** 本地资源存放目录（两个小程序包内保持一致） */
    private static final String DIR = "/assets/product/";

    private static final Map<String, String> PRESET_TO_PATH;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 当前为空：平台不再提供自制图。恢复时在此登记 key -> DIR + "xxx.webp"
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

    /** 全部预设 key（下发给站长端选图面板）；当前为空 Map */
    public static Map<String, String> all() {
        return PRESET_TO_PATH;
    }
}
