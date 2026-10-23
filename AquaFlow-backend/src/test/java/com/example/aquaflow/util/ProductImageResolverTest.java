package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProductImageResolver} 的解析口径验证。
 *
 * <p>为什么值得单独测：这是包内图、永久原图与COS对象键的分叉点，
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
    @DisplayName("公开永久品牌原图不经过COS，三种容量分别保留对应图片")
    void permanentBrandPhotosDoNotNeedCos() {
        ProductImageResolver resolver = new ProductImageResolver(obj -> {
            fail("公开原图不得进入COS签名器");
            return null;
        });
        for (String url : new String[] {
                "https://file-cloud.yst.com.cn/photo/website/2025/02/26/f554d116c81e40e7ac07ca7fc4e48e37.png",
                "https://file-cloud.yst.com.cn/website/2022/12/14/a5e74273e5e94eabaa2545ffe13e9b70.png",
                "https://file-cloud.yst.com.cn/photo/website/2025/02/26/91e8adc5b45a4aa8af2ae1c4fc554504.png"}) {
            assertEquals(url, resolver.resolve(url));
        }
    }

    @Test
    @DisplayName("已入库的三张普利思对象键始终使用对应永久原图，不进入COS签名")
    void verifiedPulisiKeysNeverDependOnCosSigner() {
        ProductImageResolver resolver = new ProductImageResolver(obj -> {
            fail("已核普利思原图不得进入COS签名器，也不能因签名失效而无图");
            return null;
        });
        String[][] photos = {
                {"catalog-sample-20261023-pulisi-purified-87bf702c165d.png",
                        "https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png", "pulisi-purified-photo"},
                {"catalog-sample-20261023-pulisi-spring-f576447ab103.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png", "pulisi-spring-photo"},
                {"catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png", "pulisi-mineral-photo"}
        };
        for (String[] photo : photos) {
            assertEquals(photo[1], resolver.resolve(photo[0]), "商品目录使用已保存的对象键读取");
            assertEquals(photo[1], resolver.resolvePresetKey(photo[2]), "选图面板与商品目录同源");
        }
    }

    @Test
    @DisplayName("不把临时签名链接、明文HTTP或非图片地址当永久品牌图")
    void invalidExternalImageDoesNotLeakIntoSigner() {
        ProductImageResolver resolver = new ProductImageResolver(obj -> {
            fail("不支持的外部URL不得被当成COS对象键");
            return null;
        });
        for (String value : new String[] {
                "https://cos.example.com/product.png?sign=expires",
                "https://example.com/product.png#fragment",
                "https://user:password@example.com/product.png",
                "http://example.com/product.png",
                "https://example.com:8080/product.png",
                "https://example.com/product.html",
                "https://example.com/bad path.png"}) {
            assertNull(resolver.resolve(value), value);
        }
    }

    @Test
    @DisplayName("COS 对象键在 COS 不可用时返回 null 而不是抛异常（单条失败不拖垮列表）")
    void cosKeyFailureIsSwallowed() {
        ProductImageResolver resolver = newResolver();
        assertNull(resolver.resolve("product/2026/09/xxx.jpg"),
                "COS 未配置时应有降级：返回 null，绝不抛异常");
    }

    /**
     * [2026-09-20] 补上**成功支**的确定性覆盖。
     *
     * <p>此前这一支只在集成测试里被"允许签成 http 或 null"这种二选一断言扫过 ——
     * 跑测试的机器 COS 配没配不确定，于是**两支谁都没被真正钉住**：把签名结果写坏、
     * 或者误把对象键原样返回，测试照样绿。这里用可注入的签名器把成功支定死。</p>
     */
    @Test
    @DisplayName("COS 对象键在签名成功时返回签名 URL（成功支的确定性覆盖）")
    void cosKeySuccessReturnsSignedUrl() {
        ProductImageResolver resolver = new ProductImageResolver(
                obj -> "https://cos.example.com/" + obj + "?sign=x");
        assertEquals("https://cos.example.com/product/2026/09/xxx.jpg?sign=x",
                resolver.resolve("product/2026/09/xxx.jpg"),
                "签名器给什么就下发什么");
    }

    /**
     * [2026-09-20] 本地路径**不得触碰签名器** —— 这条以前只是"顺带"被返回值断言覆盖：
     * 若有人把两个分支的顺序调换，本地路径会先被送去签名；只要签名器恰好能签出东西，
     * 断言仍会过，而真机上就变成"包内图被换成 COS 链接"（COS 一旦挂掉，商品图全没）。
     */
    @Test
    @DisplayName("本地路径绝不调用签名器（换序也会被这条抓住）")
    void localPathNeverTouchesSigner() {
        int[] calls = {0};
        ProductImageResolver resolver = new ProductImageResolver(obj -> {
            calls[0]++;
            return "https://cos.example.com/" + obj;
        });

        String path = "/assets/product/pulisi-pure.webp";
        assertEquals(path, resolver.resolve(path));
        assertEquals(0, calls[0],
                "以 / 开头的本地资源必须直接返回，一次都不能送去签名");
    }

    @Test
    @DisplayName("中性图预设原样返回，未知和已废弃的预设key仍返回null")
    void presetKeyMapping() {
        ProductImageResolver resolver = newResolver();
        assertEquals("/assets/product/barrel-neutral-template.webp",
                resolver.resolvePresetKey("barrel-neutral-template"));
        // 当前有明确登记的预设，但不能恢复已经移除的旧key或按名字猜图。
        assertNull(resolver.resolvePresetKey("barrel-water"),
                "历史 key 不得再映射出路径（对应文件已删除，返回路径就是死链）");
        assertNull(resolver.resolvePresetKey("no-such-key"));
        assertNull(resolver.resolvePresetKey(null));
        assertNull(resolver.resolvePresetKey(""));
    }
}
