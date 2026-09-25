package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送计费端到端（v35）：起送量 / 配送范围 / 运费 / 楼层费。
 *
 * <p>规则本身由 {@code DeliveryFeeUtilTest}（纯单元测试）穷举覆盖。本用例只管两件只有走真 HTTP
 * 才能验的事：</p>
 * <ol>
 *   <li><b>报价与下单同口径</b> —— 同一个购物车、同一个地址，{@code /api/payments/quote} 的
 *       {@code totalAmount} 必须等于下单落库的 {@code orders.total_amount}。
 *       这是本仓"计价双轨"事故（结算页一个价、订单另一个价 → 客诉）的回归口径；</li>
 *   <li><b>费用落独立列</b> —— 绝不并入 {@code water_amount} 或 {@code deposit_amount}
 *       （后者是可退押金，混入会导致取消订单多退钱）。</li>
 * </ol>
 */
class DeliveryFeeIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("报价与下单同口径：基础运费 + 无电梯楼层费都算进 totalAmount，且两处相等")
    void quoteAndCreateOrderAgree() {
        long[] ids = seedConfiguredStation();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        String mgr = staffToken(ids[4], "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 基础运费 3 元；无电梯每超一层 2 元（免费层 1）
        Api saved = put("/api/manager/delivery-config", mgr,
                "{\"baseDeliveryFee\":3.00,\"floorFeePerLevel\":2.00,\"floorFreeLevel\":1}");
        assertEquals(0, saved.code(), "站长保存计费配置: " + saved);

        Api quote = quote(cus, station, address, product, 2);
        assertEquals(0, quote.code(), "试算: " + quote);
        BigDecimal quoteTotal = new BigDecimal(quote.data().path("totalAmount").asText());
        BigDecimal quoteDelivery = new BigDecimal(quote.data().path("deliveryFee").asText());
        BigDecimal quoteFloor = new BigDecimal(quote.data().path("floorFee").asText());
        assertEquals(0, quoteDelivery.compareTo(new BigDecimal("3.00")), "报价里的基础运费: " + quote);
        assertEquals(0, quoteFloor.compareTo(new BigDecimal("10.00")),
                "6 层无电梯、免费 1 层 → 超 5 层 × 2 元 = 10 元: " + quote);

        Api created = order(cus, station, address, product, 2);
        assertEquals(0, created.code(), "下单: " + created);
        long orderId = created.data().path("orderId").asLong();

        BigDecimal water = decimalOf("SELECT water_amount FROM orders WHERE id=?", orderId);
        BigDecimal deposit = decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId);
        BigDecimal deliveryFee = decimalOf("SELECT delivery_fee FROM orders WHERE id=?", orderId);
        BigDecimal floorFee = decimalOf("SELECT floor_fee FROM orders WHERE id=?", orderId);
        BigDecimal total = decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId);

        assertEquals(0, deliveryFee.compareTo(new BigDecimal("3.00")), "运费应落独立列");
        assertEquals(0, floorFee.compareTo(new BigDecimal("10.00")), "楼层费应落独立列");
        // ⚠️ 关键断言：费用不得污染水费与押金
        assertEquals(0, water.compareTo(new BigDecimal("40.00")), "水费必须仍是 20×2=40，不含运费");
        assertEquals(0, deposit.compareTo(new BigDecimal("60.00")), "押金必须仍是 30×2=60，不含运费");
        assertEquals(0, total.compareTo(water.add(deposit).add(deliveryFee).add(floorFee)),
                "总额 = 水费 + 押金 + 运费 + 楼层费");
        // ⚠️ 最关键的一条：报价与下单必须相等
        assertEquals(0, quoteTotal.compareTo(total),
                "报价 totalAmount(" + quoteTotal + ") 必须等于下单落库 total_amount(" + total
                        + ") —— 不等就是计价双轨，客户会投诉");
    }

    @Test
    @DisplayName("没配过配置的水站：费用恒 0，total 仍等于 水费 + 押金（升级前行为一个字不变）")
    void unconfiguredStationBehavesAsBefore() {
        long station = createStation("未配置站");
        long manager = createStaff("未配置站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("未配置客户", "feecfg-none-openid");
        long address = createAddress(customer, "未配置小区 1 号");
        long product = createProduct("未配置水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");

        Api created = order(customerToken(customer), station, address, product, 2);
        assertEquals(0, created.code(), "下单: " + created);
        long orderId = created.data().path("orderId").asLong();

        assertEquals(0, decimalOf("SELECT delivery_fee FROM orders WHERE id=?", orderId).compareTo(BigDecimal.ZERO));
        assertEquals(0, decimalOf("SELECT floor_fee FROM orders WHERE id=?", orderId).compareTo(BigDecimal.ZERO));
        assertEquals(0, decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId)
                        .compareTo(new BigDecimal("100.00")),
                "水费 40 + 押金 60 = 100，一分钱都不该多出来");
    }

    @Test
    @DisplayName("起送量 REJECT：报价返回 blocked+原因，下单被拒（两侧判据同源）")
    void rejectModeBlocksQuoteAndOrder() {
        long[] ids = seedConfiguredStation();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        String mgr = staffToken(ids[4], "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertEquals(0, put("/api/manager/delivery-config", mgr,
                "{\"minOrderBuckets\":5,\"minOrderMode\":\"REJECT\"}").code());

        // 只买 2 桶，未达 5 桶起送量
        Api quote = quote(cus, station, address, product, 2);
        assertEquals(0, quote.code(), "试算本身不报错，而是把结论放在 blocked 里: " + quote);
        assertTrue(quote.data().path("blocked").asBoolean(), "报价应标 blocked: " + quote);
        assertTrue(quote.data().path("blockReason").asText().contains("未达起送量"),
                "原因应可读: " + quote.data().path("blockReason").asText());

        Api created = order(cus, station, address, product, 2);
        assertNotEquals(0, created.code(), "下单必须被同样的判据拒绝: " + created);
        assertEquals(0, intOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station), "被拒时不得留下订单");

        // 达门槛后放行
        assertEquals(0, order(cus, station, address, product, 5).code(), "达到 5 桶应放行");
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders WHERE station_id=?", station));
    }

    @Test
    @DisplayName("配送范围：坐标齐全时超范围拦单；站点没选点时放行（拿不准就不判）")
    void radiusUsesRealDistanceButSkipsWhenUnknown() {
        long[] ids = seedConfiguredStation();
        long station = ids[0], customer = ids[1], address = ids[2], product = ids[3];
        String mgr = staffToken(ids[4], "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 半径 1 公里 + 超范围拒单
        assertEquals(0, put("/api/manager/delivery-config", mgr,
                "{\"deliveryRadiusM\":1000,\"overRadiusMode\":\"REJECT\"}").code());

        // seedConfiguredStation 里：站点 (39.9000,116.4000)，地址 (39.9000,116.4200) ≈ 1.7 公里 → 超范围
        Api quote = quote(cus, station, address, product, 2);
        assertTrue(quote.data().path("blocked").asBoolean(), "1.7 公里超出 1 公里半径应被拦: " + quote);
        assertNotEquals(0, order(cus, station, address, product, 2).code(), "下单同样被拒");

        // 清除站点坐标 → 距离算不出来 → 放行（并给提示，不拦单）
        assertEquals(0, put("/api/stations/mine/coordinates", mgr, "{\"lat\":null,\"lng\":null}").code());
        Api quote2 = quote(cus, station, address, product, 2);
        assertFalse(quote2.data().path("blocked").asBoolean(),
                "站点没坐标时绝不能拦单 —— 那会把所有客户挡在门外: " + quote2);
        assertEquals(0, order(cus, station, address, product, 2).code(), "此时应能正常下单");
    }

    @Test
    @DisplayName("不传 addressId 的试算：不因距离/楼层拦单，但会提示（存量调用方不传也不报错）")
    void quoteWithoutAddressStillWorks() {
        long[] ids = seedConfiguredStation();
        long station = ids[0], product = ids[3];
        String mgr = staffToken(ids[4], "STATION_MANAGER", station);
        String cus = customerToken(ids[1]);

        assertEquals(0, put("/api/manager/delivery-config", mgr,
                "{\"deliveryRadiusM\":1000,\"overRadiusMode\":\"REJECT\",\"floorFeePerLevel\":2.00}").code());

        Api quote = post("/api/payments/quote", cus,
                "{\"stationId\":" + station + ",\"paymentMethod\":1,"
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}");
        assertEquals(0, quote.code(), "不传地址不应报错: " + quote);
        assertFalse(quote.data().path("blocked").asBoolean(), "没有地址就无从判断距离，不得拦单: " + quote);
        assertEquals(0, new BigDecimal(quote.data().path("floorFee").asText()).compareTo(BigDecimal.ZERO),
                "没有地址就取不到楼层，不得收楼层费");
    }

    @Test
    @DisplayName("配置 API：站点取自登录态、负值归零、非法模式回落 WARN")
    void configApiGuards() {
        long station = createStation("配置站");
        long otherStation = createStation("配置他站");
        long manager = createStaff("配置站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 没配过时返回默认配置而不是 404
        Api before = get("/api/manager/delivery-config", mgr);
        assertEquals(0, before.code(), "读配置: " + before);
        assertFalse(before.data().path("configured").asBoolean(), "初始应为未配置");
        assertEquals(0, new BigDecimal(before.data().path("config").path("baseDeliveryFee").asText())
                .compareTo(BigDecimal.ZERO), "未配置时费用为 0");

        // 负值归零 + 非法模式回落 WARN + 请求体里的 stationId 被忽略
        Api saved = put("/api/manager/delivery-config", mgr,
                "{\"stationId\":" + otherStation + ",\"baseDeliveryFee\":-99.00,"
                        + "\"minOrderMode\":\"DELETE_EVERYTHING\",\"floorFeePerLevel\":-1,\"floorFreeLevel\":-3}");
        assertEquals(0, saved.code(), "保存: " + saved);
        assertEquals(1, intOf("SELECT COUNT(*) FROM station_delivery_config WHERE station_id=?", station),
                "必须写在本站长自己的站上");
        assertEquals(0, intOf("SELECT COUNT(*) FROM station_delivery_config WHERE station_id=?", otherStation),
                "请求体里的 stationId 必须被忽略（否则站长能改别人站的配置）");
        assertEquals(0, new BigDecimal("0.00")
                        .compareTo(decimalOf("SELECT base_delivery_fee FROM station_delivery_config WHERE station_id=?",
                                station)),
                "负值必须归零（负数会让总额变小，属于能少收钱的输入）");
        assertEquals("WARN",
                jdbc.queryForObject("SELECT min_order_mode FROM station_delivery_config WHERE station_id=?",
                        String.class, station),
                "非法模式必须回落 WARN —— 绝不能因为配置写错就让客户下不了单");
        assertEquals(0, intOf("SELECT floor_free_level FROM station_delivery_config WHERE station_id=?", station),
                "floorFreeLevel 传 -3 归零（不强制回默认 1，因为 0 层也是合法配置）");
    }

    // ---------- helpers ----------

    /** @return {station, customer, address, product, manager} */
    private long[] seedConfiguredStation() {
        long station = createStation("计费站");
        long manager = createStaff("计费站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("计费客户", "feecfg-openid");
        long address = createAddress(customer, "计费小区 1 号");
        long product = createProduct("计费水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        // 站点与地址都给坐标，且相距约 1.7 公里（0.02 度经度 @ 北纬 39.9）
        assertEquals(0, put("/api/stations/mine/coordinates", mgr,
                "{\"lat\":39.900000,\"lng\":116.400000}").code(), "站点选点");
        // 地址：6 层、无电梯、带坐标
        assertEquals(0, put("/api/addresses/" + address, customerToken(customer),
                "{\"name\":\"计费客户\",\"detail\":\"计费小区 1 号\",\"floor\":6,\"hasElevator\":0,"
                        + "\"lat\":39.900000,\"lng\":116.420000}").code(), "地址楼层与坐标");

        return new long[]{station, customer, address, product, manager};
    }

    private Api quote(String customerToken, long stationId, long addressId, long productId, int qty) {
        return post("/api/payments/quote", customerToken,
                "{\"stationId\":" + stationId + ",\"paymentMethod\":1,\"addressId\":" + addressId + ","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }

    private Api order(String customerToken, long stationId, long addressId, long productId, int qty) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + addressId + ",\"stationId\":" + stationId + ",\"paymentMethod\":1,"
                        + "\"idempotencyKey\":\"fee-order-" + stationId + "-" + productId + "-" + qty + "\","
                        + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}");
    }
}
