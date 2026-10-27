package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品保存与配送金额建议分离；已保存不限有效，建议不改变计费或强迫配置正金额。 */
@DisplayName("配送计费引导：商品先保存，金额建议可选，未保存计费仍待确认")
class DeliveryConfigGuideIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long manager;
    private String mgrToken;
    private long disposable;   // category=2 瓶装水/一次性桶
    private long barrel;       // category=1 桶装水

    private void seed() {
        station = createUnconfiguredStation("引导站");
        manager = createStaff("引导站长", "STATION_MANAGER", station, 1);
        mgrToken = staffToken(manager, "STATION_MANAGER", station);
        // 桶装水 12 元/桶（本站最便宜的一桶水 → 建议 起送 24、免运费 36）
        barrel = createProduct("引导站桶装水", 1, "12.00", "50.00", 0, "0.00");
        disposable = createProduct("引导站一次性桶 15L", 2, "22.00", "0.00", 0, "0.00");
        // ⚠️ 库存行刻意建成 **enabled=0（未上架）**：本类要验证的正是"上架那一刻"的校验，
        // 夹具若默认已上架，那条断言就失去意义（createInventoryFull 写死 enabled=1，故这里直插）。
        offShelfInventory(barrel);
        offShelfInventory(disposable);
    }

    /** 建一行"已选品但未上架"的库存（enabled=0），让"上架"成为一个真实动作。 */
    private void offShelfInventory(long productId) {
        jdbc.update("INSERT INTO inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price) "
                + "VALUES(?,?,100,0,0,0.00)", station, productId);
    }

    /** 只配桶数门槛、不配金额门槛 —— 正是产品说的那种"以为设了门槛"的配置。 */
    private void configBucketOnly() {
        Api res = put("/api/manager/delivery-config", mgrToken,
                "{\"minOrderBuckets\":2,\"minOrderMode\":\"REJECT\",\"freeDeliveryBuckets\":2,"
                        + "\"baseDeliveryFee\":3.00}");
        assertEquals(0, res.code(), "保存桶数门槛应成功: " + res);
    }

    private Api shelves(long productId) {
        return put("/api/manager/catalog/" + productId, mgrToken, "{\"enabled\":1,\"salePrice\":12.00}");
    }

    /* ==================== ① 强制：缺金额门槛时不许上架非桶装 ==================== */

    @Test
    @DisplayName("未设金额门槛仍可上架，已有规则就绪并保留可选建议")
    void shelvingNonBarrelKeepsAmountSuggestionsOptional() {
        seed();
        configBucketOnly();

        Api saved = shelves(disposable);
        assertEquals(0, saved.code(), "缺金额门槛不应阻止上架");
        // 用 COUNT 而不是标量查询：0 行时标量查询会抛异常，反而看不出"行没了"还是"没改"
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=?",
                        station, disposable),
                "被拒的上架不该动到库存行（行本身要还在）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND product_id=? AND enabled=1",
                        station, disposable),
                "商品应已上架");

        Api guide = get("/api/manager/setup-guide", mgrToken);
        assertEquals(0, guide.code());
        assertTrue(guide.data().toString().contains("起送金额"));
        assertTrue(guide.data().toString().contains("免运费金额"));

        // 桶装水上架不受这道校验影响（它走桶数门槛，本来就是对的）
        assertEquals(0, shelves(barrel).code(), "桶装水上架不该被金额门槛校验拦住");

        // 补齐金额门槛后，一次性桶可以上架
        assertEquals(0, put("/api/manager/delivery-config", mgrToken,
                "{\"minOrderBuckets\":2,\"minOrderAmount\":24.00,\"minOrderMode\":\"REJECT\","
                        + "\"freeDeliveryBuckets\":2,\"freeDeliveryAmount\":36.00,\"baseDeliveryFee\":3.00}").code());
        assertEquals(0, shelves(disposable).code(), "补齐金额门槛后应可上架，实际=" + shelves(disposable));
    }

    @Test
    @DisplayName("没配过配送计费（两条规则都没启用）时，上架非桶装不被拦 —— 不打扰没用这条规则的站")
    void noConfigDoesNotBlockShelving() {
        seed();
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_delivery_config WHERE station_id=?", station));
        assertEquals(0, shelves(disposable).code(),
                "本站没启用起送量/免运费规则时，非桶装单本来就不受门槛约束，不该拦上架");
    }

    @Test
    @DisplayName("下架（enabled=0）不受这道校验影响 —— 别把'不卖了'也卡住")
    void disablingDoesNotRequireAmountThresholds() {
        seed();
        configBucketOnly();
        // 先直接落一行"已上架"的库存（绕过校验），再验证关掉上架能成功
        jdbc.update("UPDATE inventory SET enabled=1 WHERE station_id=? AND product_id=?", station, disposable);

        assertEquals(0, put("/api/manager/catalog/" + disposable, mgrToken,
                "{\"enabled\":0}").code(), "下架必须放行");
        assertEquals(0, intOf("SELECT IFNULL(enabled,0) FROM inventory WHERE station_id=? AND product_id=?",
                station, disposable), "下架后 enabled 应为 0");
    }

    /* ==================== ② 引导：接口下发缺项与建议值 ==================== */

    @Test
    @DisplayName("配置接口下发引导：缺项列表 + 建议值（最低水价 ×2 / ×3）与依据说明")
    void guidanceIsDownlinkedWithSuggestions() {
        seed();
        configBucketOnly();
        // 模拟"历史已上架"：桶装水在架（12 元 → 建议值依据）、非桶装也在架（触发"要不要提醒"）
        jdbc.update("UPDATE inventory SET enabled=1 WHERE station_id=?", station);

        Api res = get("/api/manager/delivery-config", mgrToken);
        assertEquals(0, res.code(), "读配置应成功: " + res);
        var g = res.data().path("guidance");
        assertTrue(g.path("nonBarrelOnShelf").asBoolean(), "本站已上架非桶装商品，要如实标记");
        assertEquals(2, g.path("requiredAmountFields").size(),
                "两项金额门槛都缺，实际=" + g.path("requiredAmountFields"));
        assertEquals(0, new BigDecimal("12.00")
                        .compareTo(new BigDecimal(g.path("cheapestWaterPrice").asText("0"))),
                "建议值依据 = 本站已上架桶装水最低价 12");
        assertEquals(0, new BigDecimal("24")
                        .compareTo(new BigDecimal(g.path("suggestedMinOrderAmount").asText("0"))),
                "起送量建议 = 12 × 2 = 24（约两桶水）");
        assertEquals(0, new BigDecimal("36")
                        .compareTo(new BigDecimal(g.path("suggestedFreeDeliveryAmount").asText("0"))),
                "免运费建议 = 12 × 3 = 36（约三桶水）");
        assertFalse(g.path("cheapestPriceBasis").isMissingNode(),
                "要下发建议值的依据说明，站长才敢用这个数");
    }

    @Test
    @DisplayName("没有已上架商品时不给建议值（不凭空捏造一个数），只让站长手动填")
    void noSuggestionWhenNothingOnShelf() {
        long emptyStation = createStation("空目录站");
        long emptyMgr = createStaff("空目录站长", "STATION_MANAGER", emptyStation, 1);
        String token = staffToken(emptyMgr, "STATION_MANAGER", emptyStation);
        assertEquals(0, put("/api/manager/delivery-config", token,
                "{\"minOrderBuckets\":2,\"minOrderMode\":\"REJECT\",\"freeDeliveryBuckets\":2}").code());

        Api res = get("/api/manager/delivery-config", token);
        var g = res.data().path("guidance");
        assertEquals(2, g.path("requiredAmountFields").size(), "桶数门槛配了、金额缺，仍要提示");
        assertTrue(g.path("suggestedMinOrderAmount").isNull(),
                "没有已上架商品 → 不给建议值，实际=" + g.path("suggestedMinOrderAmount"));
        assertTrue(g.path("suggestedFreeDeliveryAmount").isNull(), "同上");
    }
}
