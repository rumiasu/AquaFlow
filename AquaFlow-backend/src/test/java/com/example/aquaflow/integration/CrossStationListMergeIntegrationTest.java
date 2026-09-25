package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨站履约单归并展示（2026-09-18 产品裁定，方案 A）。
 *
 * <p>产品原话：「跨站单订单<b>可以算</b>，只是不能看用户画像，但是可以把跨站单<b>统一成一个</b>，
 * 统一看接了多少跨站单。」落地形状：履约站的站长端「订单」页不把别站单逐个铺开，
 * 而是给一行「跨站履约 N 单 · 合计 ¥X · 待送/在送/已送」，点开才看逐单明细。</p>
 *
 * <p>本类盯四件事：</p>
 * <ol>
 *   <li><b>按履约站取数</b>：只有"本站去送、别站下的单"才算本站的跨站履约单；
 *       归属站自己调这个端点应当是 0（那单是它下的，不是它送的）；</li>
 *   <li><b>可以算</b>：总数、金额合计、按状态分类都要对（这就是"统一看接了多少跨站单"）；</li>
 *   <li><b>不能看画像</b>：明细里 {@code customerName} / {@code customerPhone} 必须为空，
 *       而订单自身的收货人快照、地址、金额照常（配送必需）；</li>
 *   <li><b>本站自己的单不算跨站</b>（同站单归属=履约），否则汇总会把两套口径搅在一起。</li>
 * </ol>
 */
@DisplayName("跨站履约单 · 归并成一条（可算、不可看画像、按履约站取数）")
class CrossStationListMergeIntegrationTest extends AbstractIntegrationTest {

    private static final String FOREIGN_CUSTOMER = "别站客户丙";
    private static final String RECEIVER = "收件人王五";
    private static final String RECEIVER_PHONE = "13700004444";
    private static final String ADDR_TEXT = "别站小区9栋1单元101";

    private long stationA;
    private long stationB;
    private long mgrA;
    private long mgrB;
    private long customerA;
    private long addressA;
    private long goods;

    private void seed() {
        stationA = createStation("归并A站");
        stationB = createStation("归并B站");
        mgrA = createStaff("归并A站长", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("归并B站长", "STATION_MANAGER", stationB, 1);
        customerA = createCustomer(FOREIGN_CUSTOMER, "merge-openid");
        addressA = createAddress(customerA, ADDR_TEXT);
        goods = createProduct("归并饮水机", 2, "20.00", "0.00", 0, "0.00");
        createInventoryFull(stationA, goods, 100, 0, "0.00");
        createInventoryFull(stationB, goods, 100, 0, "0.00");
    }

    private String tokenA() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(mgrB, "STATION_MANAGER", stationB);
    }

    /** A 站的一张待配送单（定向外派给 B 之后就是"B 的跨站履约单"）。 */
    private long crossOrder(String key) {
        long id = createOrderCrossStation(customerA, addressA, stationA, stationB, goods,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "0.00", "40.00");
        jdbc.update("UPDATE orders SET receiver_name = ?, receiver_phone = ?, address_snapshot = ?, "
                + "idempotency_key = ? WHERE id = ?", RECEIVER, RECEIVER_PHONE, ADDR_TEXT, key, id);
        return id;
    }

    @Test
    @DisplayName("B 站：跨站履约单归并计数（总数/金额/按状态），明细无客户画像、有收货人与地址")
    void crossStationSummaryCountsAndMasksProfile() {
        seed();
        long pending = crossOrder("merge-1");
        long completed = crossOrder("merge-2");
        jdbc.update("UPDATE orders SET status = 4, total_amount = 55.00 WHERE id = ?", completed);

        JsonNode data = get("/api/delivery/orders/cross-station", tokenB()).data();
        assertEquals(2, data.path("count").asInt(), "B 站接了 2 张跨站单");
        assertEquals(0, new java.math.BigDecimal("95.00").compareTo(data.path("amountTotal").decimalValue()),
                "金额合计 = 40 + 55，实际=" + data.path("amountTotal"));
        assertEquals(1, data.path("pending").asInt(), "待配送 1");
        assertEquals(1, data.path("completed").asInt(), "已完成 1");

        JsonNode rows = data.path("orders");
        assertEquals(2, rows.size());
        JsonNode row = null;
        for (JsonNode n : rows) {
            if (n.path("id").asLong() == pending) {
                row = n;
            }
        }
        assertNotNull(row, "明细里应有那张待配送的跨站单");
        assertTrue(row.path("customerName").isNull(), "别站客户姓名不得下发（可算，但不能看画像）");
        assertTrue(row.path("customerPhone").isNull(), "别站客户手机号不得下发");
        assertFalse(data.toString().contains(FOREIGN_CUSTOMER), "响应里任何字段都不该出现别站客户姓名");
        assertEquals(RECEIVER, row.path("receiverName").asText(), "收货人快照照常（配送必需）");
        assertEquals(RECEIVER_PHONE, row.path("receiverPhone").asText(), "收货人电话照常");
        assertEquals(ADDR_TEXT, row.path("addressSnapshot").asText(), "送到哪必须能看到");
    }

    @Test
    @DisplayName("归属站 A 自己调：0 张跨站履约单（那单是它下的、不是它送的）")
    void ownerStationSeesNoCrossStationOrders() {
        seed();
        crossOrder("merge-3");

        JsonNode data = get("/api/delivery/orders/cross-station", tokenA()).data();
        assertEquals(0, data.path("count").asInt(), "归属站不是履约站，不该把它算成自己的跨站履约单");
        assertEquals(0, data.path("orders").size(), "明细也应为空");
    }

    @Test
    @DisplayName("本站自己的单不计入跨站汇总；已取消的跨站单也不计（取消不算「接了多少单」）")
    void ownOrdersAndCancelledAreExcluded() {
        seed();
        long ownCustomer = createCustomer("本站客户丁", "merge-openid-b");
        long ownAddress = createAddress(ownCustomer, "本站地址1号");
        createOrderFull(ownCustomer, ownAddress, stationB, goods,
                1, 1, 2, "40.00", "0.00", "40.00", false, 0);
        long cancelled = crossOrder("merge-4");
        jdbc.update("UPDATE orders SET status = 5 WHERE id = ?", cancelled);

        JsonNode data = get("/api/delivery/orders/cross-station", tokenB()).data();
        assertEquals(0, data.path("count").asInt(),
                "本站自己的单（归属=履约）与已取消的跨站单都不该出现在跨站汇总里");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE station_id=? AND delivery_station_id=?", stationB, stationB),
                "造数核对：本站单的归属站与履约站同值 —— 它不是跨站单，所以上面那条 0 才成立");
    }
}
