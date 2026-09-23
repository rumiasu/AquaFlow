package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProductImageResolver} 的解析口径验证。
 *
 * <p>为什么值得单独测：这是"平台预设图"与"COS 对象键"两种口径的分叉点，
 * 判据写错的表现是<b>静默无图</b>（不是报错），肉眼很难发现。</p>
 */
class ProductImageResolverTest {

    /** 造一个解析器：签名器固定抛异常（模拟本机 COS 未配置） */
    private ProductImageResolver newResolver() {
        return new ProductImageResolver(obj -> {
            throw new IllegalStateException("COS 未配置");
        });
    }

    @Test
    @DisplayName("本地资源路径原样返回，且不触碰 COS（COS 未配置也不受影响）")
    void localPathPassThrough() {
        ProductImageResolver resolver = newResolver();
        String path = "/assets/product/pulisi-pure.webp";
        assertEquals(path, resolver.resolve(path),
                "以 / 开头的本地路径必须原样返回 —— 这是预设图能显示的根本");
    }

    @Test
    @DisplayName("null / 空串返回 null，不抛异常")
    void emptyReturnsNull() {
        ProductImageResolver resolver = newResolver();
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve(""));
    }

    @Test
    @DisplayName("COS 对象键在 COS 不可用时返回 null 而不是抛异常（单条失败不拖垮列表）")
    void cosKeyFailureIsSwallowed() {
        ProductImageResolver resolver = newResolver();
        assertNull(resolver.resolve("product/2026/09/xxx.jpg"),
                "COS 未配置时应有降级：返回 null，绝不抛异常");
    }

    @Test
    @DisplayName("预设 key 映射：清单当前为空，任何 key 都返回 null（绝不猜路径）")
    void presetKeyMapping() {
        ProductImageResolver resolver = newResolver();
        // 2026-09-20 口径：平台自制图已移除、只用品牌官网实拍图，
        // 预设清单为空 —— 因此任何 key（哪怕是历史 key）都必须返回 null，
        // 绝不能"猜"出一条路径来：猜错的表现是界面显示别人的图。
        assertNull(resolver.resolvePresetKey("barrel-water"),
                "历史 key 不得再映射出路径（对应文件已删除，返回路径就是死链）");
        assertNull(resolver.resolvePresetKey("no-such-key"));
        assertNull(resolver.resolvePresetKey(null));
        assertNull(resolver.resolvePresetKey(""));
    }
}
