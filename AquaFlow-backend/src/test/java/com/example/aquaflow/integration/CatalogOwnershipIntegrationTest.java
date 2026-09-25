package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1（docs/design/12-商品与库存重构.md §5/§6）：<b>通用商品库 + 本站自定义商品的归属与可见性</b>。
 *
 * <p>这批用例锁的是四件重构前做不到的事：</p>
 * <ol>
 *   <li><b>站长改不动通用库</b>：目录字段（名称/规格/图片/描述/基础价）由开发者维护 ——
 *       重构前站长一次 PUT 就能把全平台商品的名称和价格改掉（缺陷 1/2）；</li>
 *   <li><b>站长停用不了通用库商品</b>：重构前 DELETE 只判断"商品存在"，一停全平台商城都没了；</li>
 *   <li><b>自定义商品只属于本站</b>：别站的列表/详情/读接口都取不到；</li>
 *   <li><b>移除本站配置前必须把库存盘到 0</b>，库存变更必须留流水。</li>
 * </ol>
 */
@DisplayName("通用库与自定义商品：归属、越权、可见性、库存流水")
class CatalogOwnershipIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private String mgrA;
    private String mgrB;
    private String cusA;
    private String cusB;

    private void seedTwoStations() {
        stationA = createStation("归属站A");
        stationB = createStation("归属站B");
        long managerA = createStaff("站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("站长B", "STATION_MANAGER", stationB, 1);
        long customerA = createCustomer("客户A", "own-openid-a");
        long customerB = createCustomer("客户B", "own-openid-b");
        mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        mgrB = staffToken(managerB, "STATION_MANAGER", stationB);
        cusA = customerToken(customerA);
        cusB = customerToken(customerB);
    }

    /** 造数用自增后缀：通用库有"同名同品牌同规格唯一键"，用例里多次造数必须能区分 */
    private static int seq = 0;

    private long platformProduct(String price, String deposit) {
        return createProduct("通用库水" + (++seq), 1, price, deposit, 0, "0.00");
    }

    private long customProduct(String token) {
        Api created = post("/api/manager/my-products", token,
                "{\"name\":\"本站自定义水" + (++seq) + "\",\"category\":1,\"price\":15.00,\"deposit\":40.00}");
        assertEquals(0, created.code(), "建自定义商品: " + created);
        return created.data().asLong();
    }

    @Test
    @DisplayName("站长改不动通用库：旧控制台已删除，站级接口结构上就没有目录字段")
    void managerCannotEditPlatformCatalogFields() {
        seedTwoStations();
        long product = platformProduct("10.00", "30.00");
        String nameBefore = jdbc.queryForObject("SELECT name FROM product WHERE id=?", String.class, product);

        // 旧的 /api/manager/products 已按设计整体删除（它是"站长能改全局商品"的载体）
        assertNotEquals(0, put("/api/manager/products/" + product, mgrA,
                "{\"name\":\"被篡改的名字\",\"price\":12.50}").code(),
                "旧控制台必须已下线");
        assertNotEquals(0, delete("/api/manager/products/" + product, mgrA).code(), "旧删除入口必须已下线");

        // 站级接口即使被塞进目录字段也只会落到"本站设置"：DTO 里根本没有 name/price 这类字段
        Api res = put("/api/manager/catalog/" + product, mgrA,
                "{\"name\":\"被篡改的名字\",\"price\":12.50,\"status\":0,\"salePrice\":12.50,\"enabled\":1}");
        assertEquals(0, res.code(), "更新本站设置应成功: " + res);

        assertEquals(nameBefore,
                jdbc.queryForObject("SELECT name FROM product WHERE id=?", String.class, product),
                "通用库商品的名称必须没被改动");
        assertEquals(0, new BigDecimal("10.00").compareTo(decimalOf("SELECT price FROM product WHERE id=?", product)),
                "通用库参考价必须没被改动（它属于平台，不属于某个站）");
        assertEquals(1, intOf("SELECT status FROM product WHERE id=?", product), "通用库商品的 status 不得被站长改写");

        // 站长的定价意图落在**本站**（inventory.sale_price）
        assertEquals(0, new BigDecimal("12.50").compareTo(decimalOf(
                "SELECT sale_price FROM inventory WHERE station_id=? AND product_id=?", stationA, product)),
                "售价应落到本站售价覆盖");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=?", stationB),
                "站 B 不该被牵连（改价只影响本站）");
    }

    @Test
    @DisplayName("站长停用不了通用库商品，也编辑不了它：只能移除本站配置")
    void managerCannotDisableOrEditPlatformProduct() {
        seedTwoStations();
        long product = platformProduct("10.00", "30.00");

        assertNotEquals(0, delete("/api/manager/my-products/" + product, mgrA).code(),
                "通用库商品不在'我的商品'里，停用不了");
        assertEquals(1, intOf("SELECT status FROM product WHERE id=?", product), "status 必须保持 1");

        assertNotEquals(0, put("/api/manager/my-products/" + product, mgrA, "{\"name\":\"改名\"}").code(),
                "通用库商品不在'我的商品'里，改不动");
        assertNotEquals(0, delete("/api/manager/my-products/" + product, mgrA).code());
        assertNotEquals(0, post("/api/manager/my-products/" + product + "/submit", mgrA, "{}").code(),
                "只能上报自己的自定义商品");

        // 站长"不卖了"的正确姿势：移除本站配置（先选上、再移除）
        assertEquals(0, post("/api/manager/catalog/" + product + "/select", mgrA, "{\"enabled\":1}").code());
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=?",
                stationA, product));
        assertEquals(0, delete("/api/manager/catalog/" + product, mgrA).code(), "库存为 0 时应可移除");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=?",
                stationA, product), "移除后本站配置行应消失");
    }

    @Test
    @DisplayName("自定义商品只属于本站：别站看不到、改不了、也读不到")
    void customProductIsInvisibleToOtherStations() {
        seedTwoStations();
        long product = customProduct(mgrA);
        assertEquals(stationA, longOf("SELECT owner_station_id FROM product WHERE id=?", product));

        // 列表隔离
        assertTrue(catalogContains(mgrA, product), "本站目录里应有它");
        assertTrue(!catalogContains(mgrB, product), "别站目录里不得出现它");

        // 写操作全部拒绝
        assertNotEquals(0, put("/api/manager/catalog/" + product, mgrB, "{\"enabled\":1}").code(),
                "别站不得配置我的自定义商品");
        assertNotEquals(0, post("/api/manager/catalog/" + product + "/select", mgrB, "{}").code());
        assertNotEquals(0, post("/api/manager/catalog/" + product + "/stock", mgrB, "{\"target\":5}").code());
        assertNotEquals(0, delete("/api/manager/catalog/" + product, mgrB).code());
        assertNotEquals(0, put("/api/manager/my-products/" + product, mgrB, "{\"name\":\"别站改名\"}").code());
        assertNotEquals(0, delete("/api/manager/my-products/" + product, mgrB).code());

        // 读隔离：顾客侧与管理侧两条路都要挡住
        assertEquals(0, get("/api/products/" + product + "?stationId=" + stationA, cusA).code(),
                "本站顾客可读");
        assertNotEquals(0, get("/api/products/" + product + "?stationId=" + stationB, cusB).code(),
                "别站顾客读不到（否则就是跨租户泄露）");
        assertNotEquals(0, get("/api/products/" + product, cusB).code(), "不带站上下文谁都读不到自定义商品");
        assertEquals(0, get("/api/products/with-stock", mgrB).code());
    }

    @Test
    @DisplayName("库存：盘点写 ADJUST 流水；有库存时不得移除本站配置")
    void stockChangeWritesAdjustRecordAndBlocksRemoval() {
        seedTwoStations();
        long product = platformProduct("10.00", "30.00");
        assertEquals(0, post("/api/manager/catalog/" + product + "/select", mgrA,
                "{\"enabled\":1,\"quantity\":0}").code());

        // 未配置过的商品不能盘点
        long otherProduct = platformProduct("11.00", "30.00");
        assertNotEquals(0, post("/api/manager/catalog/" + otherProduct + "/stock", mgrA, "{\"target\":5}").code(),
                "没选用过的商品不能直接盘点");

        assertEquals(0, post("/api/manager/catalog/" + product + "/stock", mgrA,
                "{\"target\":8,\"note\":\"实盘 8 桶\"}").code());
        assertEquals(8, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationA, product));
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                        + "AND type='ADJUST' AND delta=8", stationA, product),
                "盘点必须留 ADJUST 流水（否则库存与流水对不上）");

        assertNotEquals(0, delete("/api/manager/catalog/" + product, mgrA).code(),
                "还有库存时不得移除本站配置");
        assertEquals(0, post("/api/manager/catalog/" + product + "/stock", mgrA, "{\"target\":0}").code());
        assertEquals(0, delete("/api/manager/catalog/" + product, mgrA).code(), "盘到 0 后可移除");
    }

    @Test
    @DisplayName("顾客可见性：下架/停售的通用库商品按 id 也读不到；别站自定义商品不在在售列表")
    void browseEndpointsRespectStatusAndOwnership() {
        seedTwoStations();
        long platform = platformProduct("10.00", "30.00");
        jdbc.update("UPDATE product SET status = 0 WHERE id = ?", platform);
        assertNotEquals(0, get("/api/products/" + platform, cusA).code(),
                "已下架商品不得再被按 id 读到（重构前任何人都能取到）");
        assertEquals(0, get("/api/products/on-sale", cusA).data().size(), "在售列表不含下架商品");

        long custom = customProduct(mgrA);
        assertEquals(0, post("/api/manager/catalog/" + custom + "/select", mgrA,
                "{\"enabled\":1,\"quantity\":5}").code());
        assertEquals(1, get("/api/products/sale-by-station?stationId=" + stationA, cusA).data().size(),
                "本站顾客能看到自家自定义商品");
        assertEquals(0, get("/api/products/sale-by-station?stationId=" + stationB, cusB).data().size(),
                "别站顾客看不到");
    }

    @Test
    @DisplayName("站级价提醒：偏离参考价过大给 warnings，但**不阻断**保存")
    void priceGuardWarnsWithoutBlocking() {
        seedTwoStations();
        long product = platformProduct("10.00", "30.00");

        Api res = post("/api/manager/catalog/" + product + "/select", mgrA,
                "{\"enabled\":1,\"salePrice\":100.00}");
        assertEquals(0, res.code(), "提醒不等于失败，保存必须成功: " + res);
        assertTrue(res.data().path("warnings").size() > 0,
                "售价是参考价的 10 倍，应给出提醒: " + res);
        assertEquals(0, new BigDecimal("100.00").compareTo(decimalOf(
                "SELECT sale_price FROM inventory WHERE station_id=? AND product_id=?", stationA, product)),
                "提醒归提醒，值照存");
    }

    @Test
    @DisplayName("上报通用库：本站可写、重复上报被拒、别站不能上报我的商品")
    void submitCustomProductToPlatform() {
        seedTwoStations();
        long product = customProduct(mgrA);

        Api first = post("/api/manager/my-products/" + product + "/submit", mgrA,
                "{\"note\":\"这个品种别的站也有需求\"}");
        assertEquals(0, first.code(), "上报应成功: " + first);
        assertEquals(1, intOf("SELECT COUNT(*) FROM product_submission WHERE station_id=? AND product_id=? "
                + "AND status=0", stationA, product));

        assertNotEquals(0, post("/api/manager/my-products/" + product + "/submit", mgrA, "{}").code(),
                "同一商品已有待处理上报时不得重复上报");
        assertEquals(1, intOf("SELECT COUNT(*) FROM product_submission WHERE station_id=?", stationA));

        assertNotEquals(0, post("/api/manager/my-products/" + product + "/submit", mgrB, "{}").code(),
                "别站不能上报我的自定义商品");
        assertEquals(0, get("/api/manager/my-products/submissions", mgrA).code(), "站长应能查自己的上报记录");
        assertEquals(0, get("/api/manager/my-products/submissions", mgrB).data().size(),
                "别站看不到我的上报");
    }

    /** 站长的"选品列表"里是否包含某商品 */
    private boolean catalogContains(String token, long productId) {
        Api res = get("/api/manager/catalog", token);
        assertTrue(res.isSuccess(), "选品列表应可读: " + res);
        for (var node : res.data()) {
            if (node.path("id").asLong() == productId) return true;
        }
        return false;
    }
}
