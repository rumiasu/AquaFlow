package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨站外派的「钱」可见性与不可变性（2026-09-18 产品裁定）。
 *
 * <p>产品原话：「配送费是站长说了算，跨站时如果是站长外派的，那还是按<b>本站的定价</b>来，
 * 但钱去向<b>实际配送的履约站</b>，而且<b>抢单前可以一眼看到</b>。」</p>
 *
 * <p>本类盯三件事（第 1 条是本类存在的主要理由）：</p>
 * <ol>
 *   <li><b>认领不重算费用</b>：单在归属站的站级计费配置下算出来的 {@code delivery_fee} /
 *       {@code floor_fee} / {@code total_amount}，被另一个站抢走之后必须<b>逐字不变</b>。
 *       抢单只改"谁去送"（{@code delivery_station_id}），不改"收多少钱"。反过来做
 *       ——在抢单时按履约站配置重算一次——就是本仓最贵的一次事故「计价双轨」
 *       （结算页一个价、下单另一个价，见 {@code util/PriceUtil} 文件头）：认领前站长看到的
 *       是 A 的价、认领后订单变成 B 的价，客户按哪个价付钱没人说得清。</li>
 *   <li><b>抢单前一眼看到钱</b>：{@code GET /api/delivery/orders/pool} 必须下发
 *       {@code deliveryFee} / {@code floorFee} / {@code totalAmount}（取 {@code orders} 快照）、
 *       定价来源站名 {@code feeStationName}、结算去向文案 {@code settleNote}。
 *       定向外派（{@code /orders/directed-incoming}）是同一件事的另一个入口，字段口径必须一致。</li>
 *   <li><b>同站单两种口径结果相同</b>：履约站==归属站时，「按归属站 {@code o.station_id} 统计」
 *       与「按履约站 {@code coalesce(delivery_station_id, station_id)} 统计」必须命中同一批单。
 *       防止哪天把口径统一到履约站时，反手把本站单算丢。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>不</b>断言"跨站单的营收算在履约站还是归属站" —— 那一层（看板 coalesce 与
 * 毛利/应收/退款/收款判权各自的口径）当前<b>尚未统一</b>，是待产品确认的事项。
 * 这里只锁"钱的计算结果不因认领而改变"。相关取证见交付报告。</p>
 */
@DisplayName("配送费可见性 · 认领不重算 / 抢单池与他站外派下发金额 / 同站单两口径一致")
class DeliveryFeePoolVisibilityIntegrationTest extends AbstractIntegrationTest {

    /** 归属站的站级计费：基础配送费 5 元；无电梯 9 层收 2 元/层（1 层免费）→ 楼层费 16 元。 */
    private static final String BASE_DELIVERY_FEE = "5.00";
    private static final String FLOOR_FEE_PER_LEVEL = "2.00";
    private static final int ADDRESS_FLOOR = 9;
    private static final int HAS_NO_ELEVATOR = 0;
    /** 报价里的水费（1 桶 × 站级售价 50 元）。 */
    private static final String WATER_AMOUNT = "50.00";

    private long stationClaim;   // 抢单/接单方（当前登录站长）
    private long stationOwner;   // 归属站（下单水站，定价来源）
    private long stationThird;   // 第三方站（用来证明池子的可见范围）
    private long mgrClaim;
    private long mgrOwner;
    private long mgrThird;
    private long driverClaim;
    private long customer;
    private long address;
    private long product;

    /** 三站 + 商品 + 客户 + 1 桶水 50 元的场景。 */
    private void seed() {
        stationClaim = createStation("抢单站");
        stationOwner = createStation("归属站");
        stationThird = createStation("第三站");
        mgrClaim = createStaff("抢单站长", "STATION_MANAGER", stationClaim, 1);
        mgrOwner = createStaff("归属站长", "STATION_MANAGER", stationOwner, 1);
        mgrThird = createStaff("第三站长", "STATION_MANAGER", stationThird, 1);
        driverClaim = createStaff("抢单站配送员", "DELIVERY", stationClaim, 1);

        product = createProduct("跨站水", 1, "50.00", "30.00", 0, "0.00");
        // 三个站都上架：归属站定价 + 抢单站自己另有一套定价（证明"不按抢单站重算"）
        createInventoryWithStationPricing(stationOwner, product, 100, "50.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(stationClaim, product, 100, "50.00", "30.00", 0, "0.00");
        createInventoryWithStationPricing(stationThird, product, 100, "50.00", "30.00", 0, "0.00");
        // 只给归属站配"贵的运费/楼层费"，抢单站一分配置都不配 → 一旦有人重算，金额立刻对不上
        insertDeliveryConfig(stationOwner);
        createCustomerStationConfig(customer = createCustomer("跨站客户", "pool-openid"), stationOwner, 1);
        address = createAddress(customer, "跨站小区 9 楼");
        jdbc.update("UPDATE address SET floor = ?, has_elevator = ? WHERE id = ?",
                ADDRESS_FLOOR, HAS_NO_ELEVATOR, address);
    }

    /** 站级配送计费配置（v35）：只配"基础配送费 + 楼层费"，起送量/范围一律不限。 */
    private void insertDeliveryConfig(long stationId) {
        jdbc.update("INSERT INTO station_delivery_config(station_id, min_order_mode, over_radius_mode, "
                        + "base_delivery_fee, floor_free_level, floor_fee_per_level, floor_fee_mode) "
                        + "VALUES (?, 'WARN', 'WARN', ?, 1, ?, 'PER_ORDER')",
                stationId, new BigDecimal(BASE_DELIVERY_FEE), new BigDecimal(FLOOR_FEE_PER_LEVEL));
    }

    /**
     * 用<b>真实报价接口</b>算出金额，再按同一份金额落订单快照。
     *
     * <p>为什么不让测试自己写死 5/16/71：写死的用例只能证明"我把 71 存进去了又读出来"，
     * 证明不了"订单里的钱是归属站配置算出来的"。报价与下单调的是同一个
     * {@code DeliveryFeeService.calcForOrder}（AGENTS §1.1），所以拿报价结果当快照
     * 既省一次完整下单链路，又保持了"金额来自真实计费"。</p>
     */
    private JsonNode quoteAtOwnerStation() {
        Api q = post("/api/payments/quote", customerToken(customer),
                "{\"stationId\":" + stationOwner + ",\"paymentMethod\":2,\"addressId\":" + address
                        + ",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
        assertEquals(0, q.code(), "归属站报价应成功: " + q);
        return q.data();
    }

    /** 按报价结果落一张"归属站在池中"的待配送现金单（履约站为空 = 在池中）。 */
    private long pendingOrderInPool(JsonNode quote) {
        return pendingOrderInPool(quote, true);
    }

    /**
     * @param withDeposit 是否照抄报价里的首单押金。
     *
     * <p>⚠️ [2026-09-18] 产品裁定「涉押金/桶权益的单禁止放入抢单池（建议直接拒单）」之后，
     * <b>凡是真的要走入池 / 抢单动作的用例都必须传 {@code false}</b> —— 押金单入池会被
     * {@code OrderWorkflowServiceImpl} 直接拒（判据 {@code involvesDepositOrBarrelRights}，
     * 拒绝行为本身由 {@code CrossStationPoolRiskAndProfileIsolationIntegrationTest} 覆盖）。
     * 本类验证的是"费用快照不因认领而变"，与押金无关，所以池子相关的用例用不含桶/押金的单造数
     * （总额相应变成 水费 50 + 配送费 5 + 楼层费 16 = 71）；同站单那一条不入池，保持带押金的 101。</p>
     */
    private long pendingOrderInPool(JsonNode quote, boolean withDeposit) {
        BigDecimal water = new BigDecimal(quote.path("waterAmount").asText());
        BigDecimal deposit = withDeposit ? new BigDecimal(quote.path("extraDeposit").asText()) : BigDecimal.ZERO;
        BigDecimal deliveryFee = new BigDecimal(quote.path("deliveryFee").asText());
        BigDecimal floorFee = new BigDecimal(quote.path("floorFee").asText());
        BigDecimal total = withDeposit
                ? new BigDecimal(quote.path("totalAmount").asText())
                : new BigDecimal("71.00");
        // 押金也照抄报价（客户在本站没有桶权益 → 首单缺桶押金 1 × 30），
        // firstBarrelOrder=false 与之对应：这一列表达的是"本站第一笔买桶订单"。
        long orderId = createOrderFull(customer, address, stationOwner, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                water.toPlainString(), deposit.toPlainString(), total.toPlainString(), false,
                withDeposit ? 1 : 0);
        // createOrderFull 的履约站默认写成归属站；池中单的判据是 delivery_station_id IS NULL
        jdbc.update("UPDATE orders SET delivery_station_id = NULL, delivery_fee = ?, floor_fee = ?, "
                        + "receiver_name = '张三', receiver_phone = '13900000000' WHERE id = ?",
                deliveryFee, floorFee, orderId);
        // 毛利报表按 order_item 汇总收入，没有明细行它只会返回空集（"看起来没有单"）
        insertOrderItemWithProductionSubtotal(orderId);
        return orderId;
    }

    /**
     * 订单明细，并**按生产口径**写 {@code subtotal}。
     *
     * <p>⚠️ 不能用基类 {@code createOrderItem} 的 subtotal：它算的是
     * {@code (price + deposit) × qty}，而 {@code OrderServiceImpl:532} 写的是
     * {@code price × qty}（单位售价 × 数量，押金另算）。本用例的数字要用在毛利报表上，
     * 用夹具那套口径会把"营收"多算一份押金（50 变 80），断言就变成在对一个错误的基准做校验。</p>
     */
    private void insertOrderItemWithProductionSubtotal(long orderId) {
        long itemId = createOrderItem(orderId, product, "跨站水", 1, WATER_AMOUNT, "30.00", 1);
        jdbc.update("UPDATE order_item SET subtotal = ? WHERE id = ?",
                new BigDecimal(WATER_AMOUNT), itemId);
    }

    private Api poolOf(String token) {
        Api res = get("/api/delivery/orders/pool", token);
        assertEquals(0, res.code(), "抢单池应可读: " + res);
        return res;
    }

    private JsonNode poolRow(Api pool, long orderId) {
        for (JsonNode n : pool.data()) {
            if (n.path("id").asLong() == orderId) return n;
        }
        return null;
    }

    /** 按订单 id 取"他站外派给我"列表里的那一行。 */
    private JsonNode incomingRow(long orderId) {
        Api res = get("/api/delivery/orders/directed-incoming",
                staffToken(mgrClaim, "STATION_MANAGER", stationClaim));
        assertEquals(0, res.code(), "他站外派列表应可读: " + res);
        for (JsonNode n : res.data()) {
            if (n.path("id").asLong() == orderId) return n;
        }
        return null;
    }

    /** 断言三个金额字段与基准逐字相等（比较 BigDecimal，避免 71 与 71.00 这类刻度假阳性）。 */
    private void assertMoney(JsonNode row, String deliveryFee, String floorFee, String total, String what) {
        assertNotNull(row, what + "：列表里应能找到这一单");
        assertEquals(0, new BigDecimal(deliveryFee).compareTo(row.path("deliveryFee").decimalValue()),
                what + "：配送费应是归属站算出的快照 " + deliveryFee + "，实际=" + row.path("deliveryFee"));
        assertEquals(0, new BigDecimal(floorFee).compareTo(row.path("floorFee").decimalValue()),
                what + "：楼层费应是归属站算出的快照 " + floorFee + "，实际=" + row.path("floorFee"));
        assertEquals(0, new BigDecimal(total).compareTo(row.path("totalAmount").decimalValue()),
                what + "：订单总额应等于快照 " + total + "，实际=" + row.path("totalAmount"));
    }

    /* ==================================================================
     *  1. 认领不重算费用（本类的主要交付物）
     * ================================================================== */

    @Test
    @DisplayName("跨站单：抢单只换履约站，delivery_fee / floor_fee / total_amount 逐字不变")
    void claimDoesNotRecalculateFees() {
        seed();
        JsonNode quote = quoteAtOwnerStation();
        // 报价本身要真的带上这两笔费用，否则本用例会退化成"0 == 0"的空断言
        assertEquals(0, new BigDecimal(BASE_DELIVERY_FEE).compareTo(quote.path("deliveryFee").decimalValue()),
                "归属站报价应含基础配送费 " + BASE_DELIVERY_FEE + "，实际=" + quote.path("deliveryFee"));
        assertEquals(0, new BigDecimal("16.00").compareTo(quote.path("floorFee").decimalValue()),
                "无电梯 9 层（1 层免费，2 元/层）应报出 16.00 楼层费，实际=" + quote.path("floorFee"));

        long orderId = pendingOrderInPool(quote, false);
        BigDecimal feeBefore = decimalOf("SELECT delivery_fee FROM orders WHERE id=?", orderId);
        BigDecimal floorBefore = decimalOf("SELECT floor_fee FROM orders WHERE id=?", orderId);
        BigDecimal totalBefore = decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId);
        assertEquals(0, new BigDecimal("71.00").compareTo(totalBefore),
                "快照总额应为 水费 50 + 配送费 5 + 楼层费 16 = 71（不含押金，见 pendingOrderInPool 的说明），实际=" + totalBefore);

        // 归属站放进抢单池（清空履约站）
        Api out = post("/api/delivery/orders/transfer/" + orderId + "/outsource",
                staffToken(mgrOwner, "STATION_MANAGER", stationOwner), "{}");
        assertTrue(out.isSuccess(), "放入抢单池应成功: " + out);
        assertEquals("NULL", jdbc.queryForObject(
                "SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?", String.class, orderId),
                "放池后履约站必须为空（这是在池中的唯一标志）");

        // 抢单站抢单
        Api claim = post("/api/delivery/orders/" + orderId + "/claim-pool",
                staffToken(mgrClaim, "STATION_MANAGER", stationClaim),
                "{\"deliveryStaffId\":" + driverClaim + "}");
        assertTrue(claim.isSuccess(), "抢单应成功: " + claim);

        assertEquals(stationClaim, longOf("SELECT delivery_station_id FROM orders WHERE id=?", orderId),
                "履约站应变为抢单站");
        assertEquals(stationOwner, longOf("SELECT station_id FROM orders WHERE id=?", orderId),
                "归属站（定价来源）不得被抢单改动");
        // ⚠️ 核心断言：三个金额逐字不变（抢单站自己没配任何配送计费，一旦重算必然变成 0）
        assertEquals(0, feeBefore.compareTo(decimalOf("SELECT delivery_fee FROM orders WHERE id=?", orderId)),
                "抢单不得重算配送费（在抢单站配置下重算会变成 0）");
        assertEquals(0, floorBefore.compareTo(decimalOf("SELECT floor_fee FROM orders WHERE id=?", orderId)),
                "抢单不得重算楼层费");
        assertEquals(0, totalBefore.compareTo(decimalOf("SELECT total_amount FROM orders WHERE id=?", orderId)),
                "抢单不得重算订单总额");
    }

    /* ==================================================================
     *  2. 抢单池「一眼看到钱」+ 池子的可见范围
     * ================================================================== */

    @Test
    @DisplayName("抢单池：抢单站看得到金额与定价来源站；放池的归属站自己看不到；第三方站看得到")
    void poolExposesMoneyAndItsScope() {
        seed();
        JsonNode quote = quoteAtOwnerStation();
        long orderId = pendingOrderInPool(quote, false);
        String ownerToken = staffToken(mgrOwner, "STATION_MANAGER", stationOwner);
        assertTrue(post("/api/delivery/orders/transfer/" + orderId + "/outsource", ownerToken, "{}").isSuccess(),
                "放入抢单池");

        // 能抢的站：三个金额 + 定价来源站名 + 结算去向文案，一次全看到
        JsonNode row = poolRow(poolOf(staffToken(mgrClaim, "STATION_MANAGER", stationClaim)), orderId);
        assertMoney(row, "5.00", "16.00", "71.00", "抢单站看到的池中单");
        assertEquals("归属站", row.path("feeStationName").asText(),
                "必须下发「定价来自哪个站」的站名（费用是按它的站级配置算的）");
        assertEquals(stationOwner, row.path("pricingStationId").asLong(), "定价来源站 id 应是归属站");
        String note = row.path("settleNote").asText();
        assertTrue(note.contains("认领后") && note.contains("你站"),
                "结算去向文案由后端下发，抢单池语境应说明认领后钱归本站，实际=" + note);
        assertTrue(note.contains("归属站"),
                "文案还应说清桶/押金/水票仍记在定价来源站（钱与资产的口径不同，不能只讲一半），实际=" + note);
        assertTrue(row.path("settleToMyStation").asBoolean(),
                "应下发「钱是否计入本站」的布尔值，前端不必自己推导");

        // 池子的过滤条件（写断言前先核过 SQL）：delivery_station_id IS NULL AND status = 1
        // AND station_id != 自己 —— 所以放池的归属站自己看不到这一单，第三方站看得到。
        assertNull(poolRow(poolOf(ownerToken), orderId),
                "归属站自己的池子里不该有自己外派出去的单（listPoolOrders 排除了 station_id = 自己）");
        JsonNode thirdRow = poolRow(poolOf(staffToken(mgrThird, "STATION_MANAGER", stationThird)), orderId);
        assertNotNull(thirdRow, "池子对所有其他站可见（谁都能抢）");
        assertMoney(thirdRow, "5.00", "16.00", "71.00", "第三方站看到的池中单");

        // ⚠️ 不能借这次改动扩大泄露面：池子是跨租户可见的，只加金额与站名，不加归属站经营信息
        java.util.List<String> leaked = new java.util.ArrayList<>();
        row.fieldNames().forEachRemaining(f -> {
            if ("costPrice".equals(f) || "costAmount".equals(f) || "inventoryQuantity".equals(f)
                    || "stationPhone".equals(f) || "stationAddress".equals(f)) {
                leaked.add(f);
            }
        });
        assertTrue(leaked.isEmpty(),
                "池中单不得带出归属站的成本/库存/联系方式（池子是跨租户可见面，新增字段要逐个过一遍），实际泄露=" + leaked);
    }

    /* ==================================================================
     *  2'. 定向外派（他站外派页签）同一口径
     * ================================================================== */

    @Test
    @DisplayName("定向外派：接收站「他站外派」列表同样一眼看到金额、定价来源站与接单后的去向")
    void directedIncomingExposesMoneyToo() {
        seed();
        // 这张单的履约站一开始就是归属站自己（定向外派的前提：仅本站履约的单可外派）
        long orderId = createOrderFull(customer, address, stationOwner, product,
                1, 1, 2, WATER_AMOUNT, "30.00", "101.00", false, 1);
        jdbc.update("UPDATE orders SET delivery_fee = ?, floor_fee = ? WHERE id = ?",
                new BigDecimal(BASE_DELIVERY_FEE), new BigDecimal("16.00"), orderId);
        insertOrderItemWithProductionSubtotal(orderId);

        // ⚠️ [2026-09-18] 这张单带首单押金（deposit=30）→ 属"涉押金单"，定向外派按产品裁定
        // 必须由外派方显式确认（riskAcknowledged=true），否则后端直接拒。
        Api dispatched = post("/api/delivery/orders/" + orderId + "/dispatch",
                staffToken(mgrOwner, "STATION_MANAGER", stationOwner),
                "{\"targetStationId\":" + stationClaim + ",\"reason\":\"人手不足\",\"riskAcknowledged\":true}");
        assertTrue(dispatched.isSuccess(), "定向外派应成功: " + dispatched);
        assertEquals(stationClaim, longOf("SELECT delivery_station_id FROM orders WHERE id=?", orderId));
        assertEquals(stationOwner, longOf("SELECT station_id FROM orders WHERE id=?", orderId), "归属站不变");

        JsonNode row = incomingRow(orderId);
        assertMoney(row, "5.00", "16.00", "101.00", "接收站看到他站外派单");
        assertEquals("归属站", row.path("feeStationName").asText(), "定价仍来自归属站（站长外派按本站定价）");
        String note = row.path("settleNote").asText();
        assertTrue(note.contains("接单后") && note.contains("你站"),
                "定向外派是「接单后」而不是「认领后」，文案由后端按语境下发，实际=" + note);
        assertTrue(row.path("settleToMyStation").asBoolean(), "接单后营收计入本站");
    }

    /* ==================================================================
     *  4. 同站单：两条站别口径必须命中同一批单
     * ================================================================== */

    @Test
    @DisplayName("同站单（归属=履约）：按归属站统计与按履约站统计结果相同，且他站看不到")
    void sameStationOrderCountsUnderBothDefinitions() {
        seed();
        JsonNode quote = quoteAtOwnerStation();
        long orderId = pendingOrderInPool(quote);
        // 召回/未外派的常态：履约站 == 归属站（createOrderFull 的默认形态）
        jdbc.update("UPDATE orders SET delivery_station_id = ? WHERE id = ?", stationOwner, orderId);
        // 商品的成本价（毛利报表要算它）
        jdbc.update("UPDATE inventory SET cost_price = 30.00 WHERE station_id = ? AND product_id = ?",
                stationOwner, product);

        String ownerToken = staffToken(mgrOwner, "STATION_MANAGER", stationOwner);
        String claimToken = staffToken(mgrClaim, "STATION_MANAGER", stationClaim);
        String today = java.time.LocalDate.now().toString();

        // (a) 按归属站统计的两条链路（毛利 / 应收）
        Api report = get("/api/manager/gross-profit?from=" + today + "&to=" + today, ownerToken);
        assertEquals(0, report.code(), "毛利报表: " + report);
        assertEquals(0, new BigDecimal("50.00").compareTo(
                        new BigDecimal(report.data().path("totalRevenue").asText())),
                "毛利报表按归属站统计到本站单的收入（1 桶 × 50）");

        Api receivables = get("/api/manager/receivables", ownerToken);
        assertEquals(0, receivables.code(), "应收台账: " + receivables);
        assertEquals(0, new BigDecimal("101.00").compareTo(
                        receivables.data().path("outstandingAmount").decimalValue()),
                "应收台账按归属站统计到本站单的待收款（水 50 + 押金 30 + 运费 5 + 楼层 16）");

        // (b) 按履约站统计的看板（coalesce(delivery_station_id, station_id)）也必须统计到同一张单
        Api dashboard = get("/api/dashboard/report?range=today", ownerToken);
        assertEquals(0, dashboard.code(), "看板: " + dashboard);
        assertEquals(0, new BigDecimal("101.00").compareTo(
                        dashboard.data().path("summary").path("grossAmount").decimalValue()),
                "看板按履约站统计时，同站单（履约=归属）必须命中同一张单");

        // (c) 反方向：抢单站两条口径都不该看到这张单（防止把本站单算给了别人）
        Api otherReport = get("/api/manager/gross-profit?from=" + today + "&to=" + today, claimToken);
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        new BigDecimal(otherReport.data().path("totalRevenue").asText())),
                "他站的毛利报表不得出现本站单");
        Api otherAr = get("/api/manager/receivables", claimToken);
        assertEquals(0, BigDecimal.ZERO.compareTo(otherAr.data().path("outstandingAmount").decimalValue()),
                "他站的应收台账不得出现本站单");
        Api otherDash = get("/api/dashboard/report?range=today", claimToken);
        assertEquals(0, BigDecimal.ZERO.compareTo(
                        otherDash.data().path("summary").path("grossAmount").decimalValue()),
                "他站的看板不得出现本站单（履约站=归属站，不是他站）");
    }
}
