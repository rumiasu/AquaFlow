package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品图片链路契约（v38：平台预设图 + 兼容 COS 对象键）。
 *
 * <p><b>为什么值得立用例：</b>这条链路的故障形态是<b>界面静默无图</b>而不是报错 ——
 * 前端只认 {@code imageUrl} 一个字段，后端少翻译一次、或把上传回填的预签名 URL
 * 当对象键存下来，界面都只是"没图"，与"这件商品本来就没配图"无法区分
 * （AGENTS §8.22 同形：零覆盖端点的真实表现是界面空白）。</p>
 *
 * <p>四条口径：① 预设图清单下发的是<b>小程序包内路径</b>；② 本地路径必须<b>原样</b>进
 * {@code imageUrl}（COS 未配置也得显示得出来）；③ COS 对象键签不出来时降级为无图、
 * 不抛异常、<b>更不当成本地路径</b>；④ 落库时 {@code imageObjectName} 优先，
 * <b>预签名 URL 绝不落库</b>（它签不出有效地址，图会永远不显示）。</p>
 */
class ProductImageIntegrationTest extends AbstractIntegrationTest {

    /**
     * 一个真实存在于两端包内的本地路径（品牌官网图）。
     * <p>⚠️ 2026-09-20 前这里用的是 {@code barrel-water.webp} —— 那是平台自制图，
     * 已按产品口径（只用官网实拍图）连同文件一起移除，故改用官网图路径。</p>
     */
    private static final String LOCAL_IMAGE_PATH = "/assets/product/pulisi-pure.webp";

    @Test
    @DisplayName("选图清单：下发包内路径，且顾客端读不到")
    void presetImageListServesPackagePaths() {
        String mgr = newManagerToken("图片站A");
        long customer = createCustomer("顾客甲", "openid-img-a");

        Api res = get("/api/manager/catalog/preset-images", mgr);
        assertEquals(0, res.code(), "选图清单应可读：" + res.data());
        assertTrue(res.data().isArray(), "必须是数组（可以为空）");

        // ⚠️ 口径（2026-09-20）：**允许为空** —— 平台只保留品牌官网实拍图，
        //    不再提供自制预设图。这里不断言数量，只断言"只要有，就必须是可用的包内路径"，
        //    否则将来增减预设图会无意义地弄红用例。
        for (JsonNode n : res.data()) {
            String path = n.path("path").asText();
            assertTrue(path.startsWith("/assets/product/"),
                    "下发的必须是两端小程序包内都存在的路径（前端直接当 src 用），实际=" + path);
        }

        assertNotEquals(0, get("/api/manager/catalog/preset-images", customerToken(customer)).code(),
                "顾客端不得读取站长选图清单");
    }

    @Test
    @DisplayName("本地预设路径原样落库、原样下发（以 / 开头即本地资源，不经 COS）")
    void localPresetPathIsPassedThrough() {
        String mgr = newManagerToken("图片站B");
        long productId = createMyProduct(mgr, "本地图商品", LOCAL_IMAGE_PATH, null);

        assertEquals(1, intOf("select count(*) from product where id = ? and image_object_name = ?",
                        productId, LOCAL_IMAGE_PATH),
                "本地路径应原样落库（不能被当成对象键去签名）");
        assertEquals(LOCAL_IMAGE_PATH, imageUrlOf(get("/api/manager/catalog", mgr).data(), productId),
                "本地预设路径必须原样下发 —— 本机 COS 未配置时，只有它能显示出来");
    }

    @Test
    @DisplayName("COS 对象键在 COS 不可用时降级为无图，不抛异常、也不冒充本地路径")
    void cosKeyDegradesInsteadOfFailing() {
        String mgr = newManagerToken("图片站C");
        long productId = createMyProduct(mgr, "对象键商品", "product/2026/09/xxx.jpg", null);

        Api list = get("/api/manager/catalog", mgr);
        assertEquals(0, list.code(), "一张图签不出来不能让整个选品列表失败（§8.21 口径）");

        String url = imageUrlOf(list.data(), productId);
        assertTrue(url == null || url.startsWith("http"),
                "COS 对象键要么签成 http 临时 URL、要么降级为 null，实际=" + url);
    }

    @Test
    @DisplayName("落库口径：imageObjectName 优先，预签名 URL 一律丢弃")
    void presignedUrlIsNeverStoredAsObjectName() {
        String mgr = newManagerToken("图片站D");

        // ① 显式对象键优先：同时传上传回填的 URL 也不能盖掉真正的对象键
        long byObjectName = createMyProduct(mgr, "对象键优先", "product/real-key.jpg",
                "https://cos.example.com/product/ignored.jpg?sign=x");
        assertEquals(1, intOf("select count(*) from product where id = ? and image_object_name = ?",
                        byObjectName, "product/real-key.jpg"),
                "imageObjectName 是落库口径，优先级必须高于上传回填的 imageUrl");

        // ② 只拿到预签名 URL 时宁可存 null（界面显示占位图），
        //    也不要存一个永远签不出来的值 —— 半坏的图比明确的"无图"更难排查
        long byUrl = createMyProduct(mgr, "只有预签名URL", null,
                "https://cos.example.com/product/x.jpg?sign=abc");
        assertEquals(1, intOf("select count(*) from product where id = ? and image_object_name is null",
                        byUrl),
                "预签名 URL 不是对象键，落库会让这张图永远显示不出来（round-trip 缺陷）");
    }

    /* ==================== 夹具 ==================== */

    private String newManagerToken(String stationName) {
        long station = createStation(stationName);
        long mgr = createStaff("站长", "STATION_MANAGER", station, 1);
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** 走 HTTP 建本站自定义商品（顺带覆盖 Controller → service 的字段传递，§8.15 口径）。 */
    private long createMyProduct(String mgrToken, String name, String imageObjectName, String imageUrl) {
        List<String> parts = new ArrayList<>();
        parts.add("\"name\":\"" + name + "\"");
        parts.add("\"category\":1");
        parts.add("\"price\":20.00");
        parts.add("\"deposit\":30.00");
        parts.add("\"quantity\":10");
        if (imageObjectName != null) {
            parts.add("\"imageObjectName\":\"" + imageObjectName + "\"");
        }
        if (imageUrl != null) {
            parts.add("\"imageUrl\":\"" + imageUrl + "\"");
        }
        String body = "{" + String.join(",", parts) + "}";

        Api res = post("/api/manager/my-products", mgrToken, body);
        assertEquals(0, res.code(), "建本站自定义商品应成功，body=" + body + " resp=" + res.data());
        return res.data().asLong();
    }

    /**
     * 从选品列表里取某商品的 {@code imageUrl}。
     *
     * <p>字段缺失与显式 null 一律当"无图"：Jackson 是否输出 null 字段取决于序列化配置，
     * 把 MissingNode 当"有值"会让用例在配置变动时静默变松。</p>
     */
    private static String imageUrlOf(JsonNode list, long productId) {
        assertTrue(list.isArray(), "选品列表应是数组，实际=" + list);
        for (JsonNode n : list) {
            if (n.path("id").asLong() == productId) {
                JsonNode img = n.path("imageUrl");
                return img.isMissingNode() || img.isNull() ? null : img.asText();
            }
        }
        fail("选品列表里找不到商品 id=" + productId + "（本站自定义商品必须出现在 /api/manager/catalog）");
        return null;
    }
}
