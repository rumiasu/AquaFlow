package com.example.aquaflow.integration;

import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桶「可用性」口径（2026-09-15 与产品确认后定稿）：
 *
 * <ul>
 *   <li><b>权益 right</b> = 已到手（押金条 remain_qty 汇总 → customer_barrel_asset.quantity）；</li>
 *   <li><b>配送中 pending</b> = 已买下未送到（customer_barrel_in_transit 里 PENDING）；</li>
 *   <li><b>持有 held</b> = 权益 + 配送中 —— 只用于<b>展示</b>（"买了就是你的"）；</li>
 *   <li><b>占用 occupied</b> = 权益 + over —— 物理在手，纯还桶上限（不含配送中）。</li>
 * </ul>
 *
 * <p>下单抵扣与退押金<b>只认权益</b>：在途的桶是给上一单的，客户手上并没有可换水的空桶。</p>
 */
@DisplayName("桶可用性：在途不抵扣 / 持有含在途 / 按桶型隔离 / 报价与下单同口径")
class BarrelAvailabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BarrelLedgerService barrelLedgerService;

    private long station;
    private long prodA;   // 农夫山泉 19L
    private long prodB;   // 娃哈哈 18.9L
    private long customer;
    private long addr;

    private void seed() {
        station = createStation("S1");
        prodA = createProduct("农夫山泉19L", 1, "22.00", "30.00", 1, "20.00");
        prodB = createProduct("娃哈哈18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, prodA, 100, 1, "20.00");
        createInventoryFull(station, prodB, 100, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
    }

    private Api order(String key, long productId, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":3,\"idempotencyKey\":\"" + key + "\","
                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}";
        return post("/api/orders/create", customerToken(customer), body);
    }

    private long orderId(String key, long productId, int qty) {
        Api res = order(key, productId, qty);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        return res.data().path("orderId").asLong();
    }

    private BigDecimal depositOf(long orderId) {
        return decimalOf("SELECT deposit_amount FROM orders WHERE id=?", orderId);
    }

    /** 已到手的权益（押金条 remain_qty 汇总）。 */
    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, prodA);
    }

    @Test
    @DisplayName("在途不抵扣：首单 2 桶还在路上时，再订 3 桶要按 3 个桶补押金")
    void inTransitDoesNotOffsetNewOrder() {
        seed();
        long o1 = orderId("k1", prodA, 2);
        assertEquals(0, depositOf(o1).compareTo(new BigDecimal("60.00")), "首单缺 2 个桶 → 押金 60");

        long o2 = orderId("k2", prodA, 3);
        assertEquals(0, depositOf(o2).compareTo(new BigDecimal("90.00")),
                "在途的 2 个桶属于上一单，不能抵扣本单 → 应补 3 个桶押金 90（旧口径只收 30）");
        assertEquals(0, right(), "两单都还没送达 → 已到手权益仍为 0");
    }

    @Test
    @DisplayName("展示口径：刚下单 持有=权益+配送中（2/0/2），送达后 持有 2、配送中 0、权益 2")
    void heldIncludesInTransitButOccupiedDoesNot() {
        seed();
        long o1 = orderId("k1", prodA, 2);

        Api before = get("/api/barrels/summary?stationId=" + station, customerToken(customer));
        assertEquals(2, before.data().path("heldBuckets").asInt(), "刚下单：持有要把在途算进去（买了就是你的）→ 2");
        assertEquals(0, before.data().path("rightBuckets").asInt(), "刚下单：已到手权益仍为 0");
        assertEquals(2, before.data().path("pendingDeliveryBuckets").asInt(), "配送中 = 2");
        assertEquals(0, before.data().path("occupiedBuckets").asInt(), "桶还没到手上 → 物理在手 0（还桶上限也是 0）");

        // 送达（走桶账唯一写入口；不跑完整配送链，避免用例耦合配送端状态机）
        barrelLedgerService.applyDelivery(o1, customer, station, new HashMap<>(), null);

        Api after = get("/api/barrels/summary?stationId=" + station, customerToken(customer));
        assertEquals(2, after.data().path("heldBuckets").asInt(), "送达后：持有仍是 2（权益顶上、配送到 0）");
        assertEquals(2, after.data().path("rightBuckets").asInt(), "送达后：权益转为已到手 2");
        assertEquals(0, after.data().path("pendingDeliveryBuckets").asInt(), "送达后配送中归零");
        assertEquals(2, after.data().path("occupiedBuckets").asInt(), "送达后桶在客户手上 → 物理在手 2（可还桶）");
    }

    @Test
    @DisplayName("按桶型也下发 占用：刚下单 持有=2 但占用=0 —— 还桶上限取占用而非持有")
    void byTypeExposesOccupiedNotHeld() {
        seed();
        long o1 = orderId("k1", prodA, 2);

        // 刚下单（还没送达）：权益 0、配送中 2、持有 2、占用 0
        JsonNode row = byTypeRow(prodA);
        assertEquals(0, row.path("assetQty").asInt(), "已到手权益仍为 0");
        assertEquals(2, row.path("inTransitQty").asInt(), "配送中只算 PENDING → 2");
        assertEquals(2, row.path("heldTotalQty").asInt(), "持有 = 权益 + 配送中（展示口径）");
        assertEquals(0, row.path("occupiedQty").asInt(),
                "占用 = 权益 + over = 0：桶还没到手上，还桶上限必须是 0"
                        + "（客户端原先拿「持有」当上限 → 会多报，提交后被后端以「交回数超过当前持有数」拒绝）");
        assertEquals(0, row.path("owedQty").asInt(), "over=0 → 既不欠桶也无暂存");

        // 送达（走桶账唯一写入口；不跑完整配送链，避免用例耦合配送端状态机）
        barrelLedgerService.applyDelivery(o1, customer, station, new HashMap<>(), null);

        // 送达后：权益 2、配送中 0、占用 2 —— 还桶上限随之打开
        JsonNode after = byTypeRow(prodA);
        assertEquals(2, after.path("assetQty").asInt(), "送达后权益转为已到手 2");
        assertEquals(0, after.path("inTransitQty").asInt(), "送达后配送中归零（DELIVERED 行不算在途）");
        assertEquals(2, after.path("heldTotalQty").asInt(), "持有仍是 2（权益顶上、配送到 0）");
        assertEquals(2, after.path("occupiedQty").asInt(), "占用转为 2：这才可以还桶");
    }

    /**
     * 权益已经退光、但人还欠着桶 —— 这时「占用」必须仍然等于 over（他还端着几个桶没还）。
     *
     * <p>怎么形成的：客户欠 2 个空桶、手上还剩 1 个付过押金的桶，他把这 1 个退掉拿回押金
     * → 权益 0、over 2，占用 = 0 + 2 = 2。他是**真的还能把这 2 个桶还回来**（后端
     * {@code returnEmpty} 的上限就是 {@code 权益 + over}），所以两个汇总端点都必须报 2。</p>
     *
     * <p>这条例用断言的是<b>两个端点同一口径</b>：概览 {@code occupiedBuckets}（客户端拿它当
     * 还桶上限）与按桶型的 {@code occupiedQty} 合计。它们曾各自只遍历
     * {@code customer_barrel_asset}，权益为 0 的商品会被整行跳过 —— 这是本仓库第 4 次
     * 踩「只遍历 assets」这个坑（前三次：员工端概览、顾客端按类型、概览的持有/押金）。</p>
     */
    @Test
    @DisplayName("权益退光但仍在欠桶：概览 占用 必须等于按桶型 占用 合计（不得因无 assets 行而算 0）")
    void occupiedCountsOwedBarrelsEvenWithoutRights() {
        seed();
        // 无 lot、无 asset 行，只有 over=+2（欠 2 个桶，占用 = 0 + 2）
        createBarrelOver(customer, station, prodA, 2);

        JsonNode row = byTypeRow(prodA);
        assertEquals(0, row.path("assetQty").asInt(), "权益已退光 → 0");
        assertEquals(2, row.path("owedQty").asInt(), "欠桶 2");
        int byTypeOccupied = row.path("occupiedQty").asInt();
        assertEquals(2, byTypeOccupied, "按桶型：占用 = 权益 + over = 0 + 2");

        Api res = get("/api/barrels/summary?stationId=" + station, customerToken(customer));
        assertTrue(res.isSuccess(), "概览应成功，实际=" + res);
        assertEquals(byTypeOccupied, res.data().path("occupiedBuckets").asInt(),
                "概览的占用必须与按桶型合计一致：客户页面拿 occupiedBuckets 当还桶上限，"
                        + "算成 0 会让客户在页面上根本还不了手上这两个桶（后端其实是允许的）");
        assertEquals(2, res.data().path("owedBuckets").asInt(), "概览欠桶 2");
    }

    /** 取「按桶型」接口里指定商品那一行；找不到直接失败，避免断言被静默跳过。 */
    private JsonNode byTypeRow(long productId) {
        Api res = get("/api/barrels/summary-by-type?stationId=" + station, customerToken(customer));
        assertTrue(res.isSuccess(), "按桶型汇总应成功，实际=" + res);
        for (JsonNode row : res.data()) {
            if (row.path("productId").asLong() == productId) return row;
        }
        throw new AssertionError("按桶型结果里没有商品 " + productId + " 的行：" + res.data());
    }

    @Test
    @DisplayName("持有 4（权益 2 + 在途 2）、再订 3 桶 → 只补 1 个桶押金")
    void onlyRightsInHandOffsetNewOrder() {
        seed();
        // 已到手权益 2：直接造押金条 + 汇总（等价于"上一单已送达"）
        createBarrelLot("LOT-A-1", customer, station, prodA, "30.00", 2, 2);
        createBarrelAsset(customer, station, prodA, 2, "60.00");
        // 在途 2（已买下未送到）
        createBarrelInTransit(customer, station, prodA, 2, "30.00", null, "PENDING");

        long o = orderId("k1", prodA, 3);
        assertEquals(0, depositOf(o).compareTo(new BigDecimal("30.00")),
                "可用（已到手）2 个、需 3 个 → 只补 1 个桶押金 30");
    }

    @Test
    @DisplayName("跨桶型不可混算：19L 手上有 2 个，不能抵 18.9L 的 3 桶需求")
    void differentBarrelTypesDoNotOffset() {
        seed();
        createBarrelLot("LOT-A-1", customer, station, prodA, "30.00", 2, 2);
        createBarrelAsset(customer, station, prodA, 2, "60.00");

        long o = orderId("k1", prodB, 3);
        assertEquals(0, depositOf(o).compareTo(new BigDecimal("90.00")),
                "A 桶型的权益不能抵 B 桶型的缺口 → 应补 3 个桶押金 90");
    }

    @Test
    @DisplayName("报价与下单同口径：即使在途存在，报价的 extraDeposit 也等于订单实收押金")
    void quoteMatchesOrderDeposit() {
        seed();
        createBarrelInTransit(customer, station, prodA, 2, "30.00", null, "PENDING");

        Api quote = post("/api/payments/quote", customerToken(customer),
                "{\"stationId\":" + station + ",\"paymentMethod\":3,"
                        + "\"items\":[{\"productId\":" + prodA + ",\"quantity\":3}]}");
        assertTrue(quote.isSuccess(), "报价应成功，实际=" + quote);
        BigDecimal quoted = new BigDecimal(quote.data().path("extraDeposit").asText());

        long o = orderId("k1", prodA, 3);
        assertEquals(0, quoted.compareTo(depositOf(o)),
                "报价押金与实收押金必须一致（两侧都只认已到手权益）：报价=" + quoted + " 实收=" + depositOf(o));
    }
}
