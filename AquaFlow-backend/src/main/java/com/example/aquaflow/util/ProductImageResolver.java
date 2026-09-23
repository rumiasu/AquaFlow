package com.example.aquaflow.util;

import com.example.aquaflow.constant.ProductImageKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 商品图片来源解析器 —— 统一"平台预设图"与"COS 对象键"两种口径。
 *
 * <p><b>为什么需要它：</b>商品图片有两种来源，前端只认一个 {@code imageUrl} 字段：</p>
 * <ol>
 *   <li><b>本地资源</b>：值形如 {@code /assets/product/pulisi-pure.webp}，是<b>小程序包内本地资源</b>，
 *       前端可直接当 {@code src} 使用，无需签名、不会过期；</li>
 *   <li><b>COS 对象键</b>：值是对象键（如 {@code product/xxx.jpg}），必须经
 *       {@link CosUtil#generatePublicUrl} 签成 24 小时有效的临时 URL 才能显示。</li>
 * </ol>
 *
 * <p><b>判据：以 {@code /} 开头即视为本地资源路径</b>（COS 对象键不带前导斜杠）。
 * 这条规则让两种口径可以共存于同一个 {@code image_object_name} 列：
 * 现在本地开发放本地路径（COS 未配置也能看到图），将来接入 COS 只需换值，<b>不用改代码</b>。</p>
 *
 * <p>⚠️ 与 cos 未配置时的表现对照：若把本地路径也丢给 {@code generatePublicUrl}，
 * 它会抛异常（被吞）→ {@code imageUrl} 恒为 null → 前端静默显示占位图，且<b>不报错</b>，
 * 极易被误判成前端缺陷。本类存在的直接目的就是避免这种静默失败。</p>
 *
 * @see ProductImageKeys 预设图 key → 路径的映射
 */
@Slf4j
@Component
public class ProductImageResolver {

    private final CosUtil cosUtil;

    /** 本地资源路径前缀：以此为开头的值一律原样返回 */
    private static final String LOCAL_PREFIX = "/";

    /**
     * 生产构造器。
     *
     * <p>⚠️ <b>这个 {@code @Autowired} 不能删</b>：本类有两个构造器（生产用的这个 +
     * 下面那个给测试用的），而 Spring 只在"<b>有且仅有一个</b>构造器"时才自动选它；
     * 多个构造器且没有任何一个标注时，它会去找<b>无参构造器</b>，找不到就抛
     * {@code No default constructor found}，<b>整个 ApplicationContext 起不来</b>
     * （2026-09-18 实际发生过：启动报 UnsatisfiedDependencyException，根因就在这里）。</p>
     */
    @Autowired
    public ProductImageResolver(CosUtil cosUtil) {
        this.cosUtil = cosUtil;
    }

    /**
     * 仅测试用构造器：绕开真实 {@link CosUtil}（它需要密钥才能构造）。
     * <p>生产代码只走上面那个构造器。</p>
     */
    ProductImageResolver(java.util.function.Function<String, String> signer) {
        this.cosUtil = null;
        this.testSigner = signer;
    }

    private java.util.function.Function<String, String> testSigner;

    /**
     * 把 {@code image_object_name} 解析成前端可直接使用的 URL。
     *
     * @param imageObjectName 本地资源路径、COS 对象键，或 null/空
     * @return 本地路径原样返回；COS 对象键返回签名 URL；无法解析时返回 null（绝不抛异常）
     */
    public String resolve(String imageObjectName) {
        if (imageObjectName == null || imageObjectName.isEmpty()) {
            return null;
        }
        // 本地资源：前端直接可用，不经 COS（也避免 COS 未配置时抛异常被吞成 null）
        if (imageObjectName.startsWith(LOCAL_PREFIX)) {
            return imageObjectName;
        }
        try {
            return testSigner != null
                    ? testSigner.apply(imageObjectName)
                    : cosUtil.generatePublicUrl(imageObjectName);
        } catch (Exception e) {
            // 单条失败不影响列表：COS 未配置时就是这种情况，保持原口径（只记日志）
            log.warn("生成商品图片URL失败, objectName={}, error={}", imageObjectName, e.getMessage());
            return null;
        }
    }

    /**
     * 把预设图 key 解析成本地资源路径。
     *
     * <p>后端下发的始终是<b>路径</b>而非 key，原因是两端小程序的包内资源互相独立、
     * 前端不该硬编码路径段；且将来换成 COS 时前端零改动。</p>
     *
     * @param presetKey 预设图 key（见 {@link ProductImageKeys}）
     * @return 本地资源路径；key 为空或未知时返回 null
     */
    public String resolvePresetKey(String presetKey) {
        return ProductImageKeys.toPath(presetKey);
    }
}
