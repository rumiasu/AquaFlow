package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一水票（站级通用票，v54）。规格见 {@code docs/design/26}，判据唯一实现在 {@code util/TicketScope}。
 *
 * <p>产品裁定：「定制优先，**统一水票**是可以设置项，比如买 10 张都打 9.5 折、30 张统一 9 折这种，
 * 定制和统一都有的情况下，定制优先，**统一的仅在没有定制水票的桶时生效**」。</p>
 *
 * <p>本类盯的四件事（每一件错了都是真金白银）：</p>
 * <ol>
 *   <li><b>账户选择</b>：有定制票余额时绝不动统一票；没有时才落站级通用票账户（{@code product_id = 0}）；</li>
 *   <li><b>流水自证</b>：消费流水记的是"订单行商品"，同时用 {@code account_product_id} 记"扣自哪个账户" ——
 *       两者混为一列会让同一张单里第二个走统一票的商品撞 {@code uk_ticket_consume} 而被静默跳过（少扣票）；</li>
 *   <li><b>退款回原账户</b>：取消订单时票必须回到**当初扣的那个**账户，而不是"按当前余额重新判定"的那个；</li>
 *   <li><b>只抵桶装水</b>：瓶装水/饮水器不占桶、没有"循环"，统一票不覆盖它们。</li>
 * </ol>
 *
 * <p>每个用例末尾都用 {@link #assertTicketBookConsistent} 复核对账 E8 的两条等式
 * （账户余额 == Σ 批次剩余；账户金额价值 == Σ 剩余×批次单价）。</p>
 */
@DisplayName("v54 · 统一水票（站级通用票）")
class UnifiedTicketIntegrationTest extends AbstractIntegrationTest {

    /**
     * 建站 + 建站长 + 建一个"本站没开水票"的桶装水商品。
     *
     * <p>刻意让 {@code inventory.ticket_enabled = 0}、{@code ticket_price = 0}：
     * 这正是统一票要服务的场景 —— 客户没有这个商品的**定制**票，本站也没为它单开水票，
     * 于是走站级通用票兜底。</p>
     */
    private long[] seedStationWithBarrel(String name, String openid) {
        long station = createStation(name);
        long manager = createStaff(name + "站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer(name + "客户", openid);
        long barrel = createProduct(name + "桶装水", 1, "20.00", "30.00", 0, "18.00");
        createInventoryFull(station, barrel, 100, 0, "0.00");
        long addr = createAddress(customer, name + "某小区1号");
        return new long[]{station, manager, customer, barrel, addr};
    }

    /** 站长挂一个统一水票档位（{@code product_id = 0}），返回档位 id。 */
    private long createUnifiedPackage(long station, String managerToken, int qty, String totalPrice) {
        Api res = post("/api/ticket-packages", managerToken,
                "{\"productId\":0,\"qty\":" + qty + ",\"price\":" + totalPrice + ",\"title\":\"统一票 " + qty + " 张\"}");
        assertEquals(0, res.code(), "站长应能给站级通用票（productId=0）建档位，实际=" + res);
        return res.data().path("id").asLong();
    }

    /** 客户按档位买统一票并让站长确认收款 → 票入站级通用账户。 */
    private long buyUnifiedTickets(long customer, long station, long packageId, int qty, String key) {
        Api purchase = post("/api/tickets/purchase", customerToken(customer),
                "{\"productId\":0,\"quantity\":" + qty + ",\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"packageId\":" + packageId + ",\"idempotencyKey\":\"" + key + "\"}");
        assertEquals(0, purchase.code(), "客户应能购买统一水票，实际=" + purchase);
        return purchase.data().path("paymentId").asLong();
    }

    private long createOrderViaApi(long customer, long addr, long station, long paymentMethod, String items) {
        Api res = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addr + ",\"stationId\":" + station + ",\"paymentMethod\":" + paymentMethod
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"items\":" + items + "}");
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        return res.data().path("orderId").asLong();
    }

    private String oneItem(long productId, int qty) {
        return "[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]";
    }

    @Test
    @DisplayName("定制优先：有定制票余额时只用定制，统一票一张不动")
    void customTicketWins() {
        long[] s = seedStationWithBarrel("优选定制站", "unified-custom-openid");
        long station = s[0], manager = s[1], customer = s[2], barrel = s[3], addr = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        long pkg = createUnifiedPackage(station, mgr, 10, "85.50");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-custom-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code(), "站长确认收款");
        assertEquals(10, unifiedBalance(customer, station), "前置：统一票 10 张已到账");

        // 客户另有一张该商品的定制票（走站长加票，单价取本站水票价）
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + barrel + ",\"quantity\":3}").code(),
                "站长给客户加 3 张定制票");

        long order = createOrderViaApi(customer, addr, station, 3, oneItem(barrel, 2));
        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":3}").code(),
                "水票支付应成功");

        assertEquals(1, customBalance(customer, station, barrel), "定制票应被扣（3-2=1）");
        assertEquals(10, unifiedBalance(customer, station), "统一票必须一张不动 —— 定制优先");
        assertEquals(barrel, longOf("SELECT account_product_id FROM ticket_record "
                        + "WHERE order_id=? AND product_id=? AND decrease_qty>0", order, barrel),
                "流水应自证扣的是该商品的定制账户");
        assertTicketBookConsistent(customer, station, barrel);
        assertTicketBookConsistent(customer, station, 0L);
    }

    @Test
    @DisplayName("统一兜底：没有定制票时用站级通用票；同单两个商品各扣一张，一条都不能少")
    void unifiedTakesOverAndTwoItemsBothDeduct() {
        long[] s = seedStationWithBarrel("统一兜底站", "unified-fallback-openid");
        long station = s[0], manager = s[1], customer = s[2], barrelA = s[3], addr = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 第二个桶装水商品：同样是本站未开定制水票
        long barrelB = createProduct("统一兜底站桶装水B", 1, "22.00", "30.00", 0, "20.00");
        createInventoryFull(station, barrelB, 100, 0, "0.00");

        long pkg = createUnifiedPackage(station, mgr, 10, "90.00");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-fallback-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());

        // ⚠️ 同一张单里两个都走统一票的商品 —— 这正是"统一票流水若写成 product_id=0 就会少扣一张"的场景
        long order = createOrderViaApi(customer, addr, station, 3,
                "[{\"productId\":" + barrelA + ",\"quantity\":1},{\"productId\":" + barrelB + ",\"quantity\":2}]");
        Api pay = post("/api/payments", customerToken(customer), "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertEquals(0, pay.code(), "两张商品都应能用统一票支付，实际=" + pay);

        assertEquals(7, unifiedBalance(customer, station), "统一票应扣 1+2=3 张（10-3=7）");
        assertEquals(2, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND decrease_qty>0", order),
                "两个商品必须各留一条消费流水（写成 product_id=0 会撞唯一键少扣一张）");
        assertEquals(1, intOf("SELECT decrease_qty FROM ticket_record WHERE order_id=? AND product_id=?",
                order, barrelA));
        assertEquals(2, intOf("SELECT decrease_qty FROM ticket_record WHERE order_id=? AND product_id=?",
                order, barrelB));
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND account_product_id<>0",
                order), "两条流水都应自证扣自站级通用票账户");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "水票支付成功即视同已付");
        assertTicketBookConsistent(customer, station, 0L);
    }

    @Test
    @DisplayName("定制不够也不吃统一票：定制 1 张 + 统一 10 张，下 2 桶的单必须整单失败且余额不动")
    void customShortageIsNotCoveredByUnified() {
        long[] s = seedStationWithBarrel("不混合站", "unified-mix-openid");
        long station = s[0], manager = s[1], customer = s[2], barrel = s[3], addr = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        long pkg = createUnifiedPackage(station, mgr, 10, "85.50");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-mix-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + barrel + ",\"quantity\":1}").code());

        long order = createOrderViaApi(customer, addr, station, 3, oneItem(barrel, 2));
        Api pay = post("/api/payments", customerToken(customer), "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertNotEquals(0, pay.code(), "定制票只有 1 张、本单要 2 张 → 整单失败（不做混合支付），实际=" + pay);

        assertEquals(1, customBalance(customer, station, barrel), "失败的支付不得扣走定制票");
        assertEquals(10, unifiedBalance(customer, station), "更不得动统一票");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND decrease_qty>0", order),
                "失败的支付不得留下任何消费流水");
        assertTicketBookConsistent(customer, station, barrel);
        assertTicketBookConsistent(customer, station, 0L);
    }

    @Test
    @DisplayName("统一票只抵桶装水：瓶装水没有定制票时，水票下单被拒（本站配了统一票也不行）")
    void unifiedDoesNotCoverBottledWater() {
        long station = createStation("非桶站");
        long manager = createStaff("非桶站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("非桶客户", "unified-bottle-openid");
        long barrel = createProduct("非桶站桶装水", 1, "20.00", "30.00", 0, "18.00");
        long bottle = createProduct("非桶站瓶装水", 2, "2.00", "0.00", 0, "0.00");
        createInventoryFull(station, barrel, 100, 0, "0.00");
        createInventoryFull(station, bottle, 100, 0, "0.00");
        long addr = createAddress(customer, "非桶站某小区1号");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        long pkg = createUnifiedPackage(station, mgr, 10, "85.50");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-bottle-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());
        assertEquals(10, unifiedBalance(customer, station), "前置：统一票 10 张");

        // 下单闸门就该拦住（商品未开定制水票，且瓶装水不在统一票范围内）
        Api res = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addr + ",\"stationId\":" + station + ",\"paymentMethod\":3"
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"items\":" + oneItem(bottle, 2) + "}");
        assertNotEquals(0, res.code(), "瓶装水不得用统一票支付，实际=" + res);
        assertTrue(res.message().contains("未开通水票支付"), "应给出可读原因，实际=" + res.message());

        // 桶装水同样条件下应当能过闸门 —— 反证上面被拒不是因为"本站没开票"这类通用原因
        assertTrue(post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addr + ",\"stationId\":" + station + ",\"paymentMethod\":3"
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"items\":" + oneItem(barrel, 1) + "}")
                .isSuccess(), "同一站同一条件下桶装水应能用统一票下单");

        assertEquals(10, unifiedBalance(customer, station), "被拒的下单不得扣票");
    }

    @Test
    @DisplayName("档位折扣只发生在购买那一刻：10 张 85.50 → 批次单价 8.55；散买（无档位）被拒")
    void unifiedPackageDiscountAndNoLooseBuy() {
        long[] s = seedStationWithBarrel("折扣站", "unified-pkg-openid");
        long station = s[0], manager = s[1], customer = s[2];
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        long pkg = createUnifiedPackage(station, mgr, 10, "85.50");

        // ⚠️ 统一票**只能按档位买**：product_id=0 既没有商品也没有库存行，
        // 散买会走"站级水票价 → product.ticket_price"两级阶梯、算出 0 元 —— 那等于白送票。
        Api loose = post("/api/tickets/purchase", customerToken(customer),
                "{\"productId\":0,\"quantity\":1,\"paymentMethod\":1,\"stationId\":" + station
                        + ",\"idempotencyKey\":\"uni-loose-1\"}");
        assertNotEquals(0, loose.code(), "统一票散买必须被拒（没有价可依），实际=" + loose);

        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-pkg-1");
        assertEquals(0, new BigDecimal("85.50").compareTo(
                        decimalOf("SELECT amount FROM payment_record WHERE id=?", payId)),
                "总价取服务端档位价");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code(), "站长确认收款");

        assertEquals(10, unifiedBalance(customer, station));
        assertEquals(0, new BigDecimal("8.55").compareTo(
                        decimalOf("SELECT unit_price FROM ticket_lot WHERE customer_id=? AND product_id=0",
                                customer)),
                "批次单价 = 实付均价 85.50/10（折扣在购买那一刻固化，用票时 1 张就是 1 张）");
        assertEquals(0, new BigDecimal("85.50").compareTo(
                        decimalOf("SELECT right_amount FROM ticket_account WHERE customer_id=? AND product_id=0",
                                customer)),
                "账户金额价值 = 10 × 8.55");
        assertTicketBookConsistent(customer, station, 0L);

        // 客户看一眼自己的水票：统一票必须有名字（product_id=0 在 product 表里没有行）
        Api accounts = get("/api/tickets?stationId=" + station, customerToken(customer));
        assertEquals(0, accounts.code(), "客户查水票: " + accounts);
        assertEquals("统一水票（站级通用）", accounts.data().get(0).path("productName").asText(),
                "统一票必须下发可读名称，否则界面显示空/未知商品");
        assertEquals(0, new BigDecimal("8.55").compareTo(
                        accounts.data().get(0).path("effectiveTicketPrice").decimalValue()),
                "统一票的面值 = 批次加权均价（它没有站级水票价可查）");
    }

    @Test
    @DisplayName("退款回原账户：用统一票付的单取消后，票回到站级通用账户，定制账户一张不多")
    void refundGoesBackToUnifiedAccount() {
        long[] s = seedStationWithBarrel("统一退款站", "unified-refund-openid");
        long station = s[0], manager = s[1], customer = s[2], barrel = s[3], addr = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        long pkg = createUnifiedPackage(station, mgr, 10, "90.00");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-refund-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());

        long order = createOrderViaApi(customer, addr, station, 3, oneItem(barrel, 3));
        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":3}").code());
        assertEquals(7, unifiedBalance(customer, station), "前置：统一票扣掉 3 张");

        Api cancel = put("/api/orders/" + order + "/customer-cancel", cus, null);
        assertTrue(cancel.isSuccess(), "取消水票已付订单应成功，实际=" + cancel);

        assertEquals(10, unifiedBalance(customer, station), "票必须退回站级通用账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account WHERE customer_id=? AND station_id=? "
                        + "AND product_id=?", customer, station, barrel),
                "⚠️ 绝不能凭空给该商品建一个定制票账户（退款时重新判定账户就会这样）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_lot WHERE customer_id=? AND product_id=?", customer, barrel),
                "定制票批次一张都不该有");
        // 回补流水的两条列各司其职：product_id 是订单行商品（幂等键用），account_product_id 是账户
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND source='退款' "
                + "AND product_id=? AND account_product_id=0", order, barrel),
                "回补流水应自证回的是统一票账户");
        assertTicketBookConsistent(customer, station, 0L);
        assertTicketBookConsistent(customer, station, barrel);
    }

    @Test
    @DisplayName("退款回**定制**账户：定制票刚好用光时取消，票必须回定制账户（重新判定会退进统一票）")
    void refundGoesBackToCustomAccountEvenWhenBalanceHitZero() {
        long[] s = seedStationWithBarrel("定制退款站", "unified-refund-custom-openid");
        long station = s[0], manager = s[1], customer = s[2], barrel = s[3], addr = s[4];
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        long pkg = createUnifiedPackage(station, mgr, 10, "85.50");
        long payId = buyUnifiedTickets(customer, station, pkg, 10, "uni-refund-custom-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm", mgr, null).code());
        // 定制票**刚好 2 张**，本单也是 2 桶 —— 付完定制票余额正好归 0
        assertEquals(0, post("/api/tickets/add", mgr,
                "{\"customerId\":" + customer + ",\"productId\":" + barrel + ",\"quantity\":2}").code(),
                "站长给客户加 2 张定制票");

        long order = createOrderViaApi(customer, addr, station, 3, oneItem(barrel, 2));
        assertEquals(0, post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":3}").code());
        assertEquals(0, customBalance(customer, station, barrel), "前置：定制票刚好用光（余额 0）");
        assertEquals(10, unifiedBalance(customer, station), "前置：统一票一张没动");

        assertTrue(put("/api/orders/" + order + "/customer-cancel", cus, null).isSuccess(), "取消订单");

        // ⚠️ 本用例是"退款必须读流水自证的账户"这条的**判别用例**：
        // 退款这一刻定制票余额是 0，重新跑一遍 TicketScope 会判成"用统一票" → 票退进统一票账户。
        assertEquals(2, customBalance(customer, station, barrel),
                "定制票必须回到该商品的定制账户");
        assertEquals(10, unifiedBalance(customer, station),
                "统一票一张都不能多 —— 客户当初根本没扣统一票");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record WHERE order_id=? AND source='退款' "
                + "AND product_id=? AND account_product_id=?", order, barrel, barrel),
                "回补流水应自证回的是定制票账户");
        assertTicketBookConsistent(customer, station, barrel);
        assertTicketBookConsistent(customer, station, 0L);
    }

    @Test
    @DisplayName("站级隔离：A 站的统一票不能在 B 站用（票按 (客户, 账户, 站) 三维隔离）")
    void unifiedTicketIsStationScoped() {
        long[] a = seedStationWithBarrel("统一A站", "unified-scope-openid");
        long stationA = a[0], managerA = a[1], customer = a[2], barrelA = a[3], addrA = a[4];
        long stationB = createStation("统一B站");
        long managerB = createStaff("统一B站站长", "STATION_MANAGER", stationB, 1);
        long barrelB = createProduct("统一B站桶装水", 1, "20.00", "30.00", 0, "18.00");
        createInventoryFull(stationB, barrelB, 100, 0, "0.00");
        long addrB = createAddress(customer, "统一B站某小区1号");

        long pkg = createUnifiedPackage(stationA, staffToken(managerA, "STATION_MANAGER", stationA), 10, "85.50");
        long payId = buyUnifiedTickets(customer, stationA, pkg, 10, "uni-scope-1");
        assertEquals(0, put("/api/payments/" + payId + "/confirm",
                staffToken(managerA, "STATION_MANAGER", stationA), null).code());

        // B 站**没配**统一票 → 下单闸门直接拦
        Api res = post("/api/orders/create", customerToken(customer),
                "{\"addressId\":" + addrB + ",\"stationId\":" + stationB + ",\"paymentMethod\":3"
                        + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"items\":" + oneItem(barrelB, 1) + "}");
        assertNotEquals(0, res.code(), "B 站没配统一票，不得用 A 站的票下单，实际=" + res);

        // B 站配了自己的统一票档位，但客户在 B 站没有票 → 真下单时仍失败（闸门只看"站里配了没"）
        createUnifiedPackage(stationB, staffToken(managerB, "STATION_MANAGER", stationB), 10, "85.50");
        long orderB = createOrderViaApi(customer, addrB, stationB, 3, oneItem(barrelB, 1));
        Api payB = post("/api/payments", customerToken(customer), "{\"orderId\":" + orderB + ",\"paymentMethod\":3}");
        assertNotEquals(0, payB.code(), "客户在 B 站没有统一票，支付必须失败，实际=" + payB);
        assertEquals(10, unifiedBalance(customer, stationA), "A 站的票一张都不能少");
        assertEquals(0, intOf("SELECT COUNT(*) FROM ticket_account WHERE customer_id=? AND station_id=?",
                customer, stationB), "B 站不得凭空出现账户");
        assertTicketBookConsistent(customer, stationA, 0L);
    }

    /* ==================== 断言辅助 ==================== */

    private int customBalance(long customerId, long stationId, long productId) {
        return intOf("SELECT COALESCE(remain_quantity,0) FROM ticket_account "
                + "WHERE customer_id=? AND station_id=? AND product_id=?", customerId, stationId, productId);
    }

    /** 站级通用票余额（账户行不存在时按 0 处理 —— 这是"还没有统一票"的正常形态）。 */
    private int unifiedBalance(long customerId, long stationId) {
        return intOf("SELECT COALESCE((SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND station_id=? AND product_id=0),0)",
                customerId, stationId);
    }

    /**
     * 复核对账 E8 的两条等式（{@code docs/design/19} §6）。
     *
     * <p>用 SQL 直接断言而不是调对账服务：E8 的内容就是这两条等式；
     * "对账服务有没有正确挂上 E8"由 {@code ReconciliationJobIntegrationTest} 覆盖。</p>
     */
    private void assertTicketBookConsistent(long customerId, long stationId, long productId) {
        int accountQty = intOf("SELECT COALESCE((SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?),0)",
                customerId, stationId, productId);
        int lotQty = intOf("SELECT COALESCE(SUM(remain_qty),0) FROM ticket_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertEquals(accountQty, lotQty,
                "E8 数量等式（product_id=" + productId + "）：账户余额必须等于 Σ 批次剩余（账户 "
                        + accountQty + " vs 批次 " + lotQty + "）");

        BigDecimal accountAmount = decimalOf("SELECT COALESCE((SELECT right_amount FROM ticket_account "
                        + "WHERE customer_id=? AND station_id=? AND product_id=?),0)",
                customerId, stationId, productId);
        BigDecimal lotAmount = decimalOf("SELECT COALESCE(SUM(remain_qty * unit_price),0) FROM ticket_lot "
                        + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1",
                customerId, stationId, productId);
        assertTrue(accountAmount.subtract(lotAmount).abs().compareTo(new BigDecimal("0.009")) <= 0,
                "E8 金额等式（product_id=" + productId + "）：账户金额价值必须等于 Σ 剩余×批次单价（账户 "
                        + accountAmount + " vs 批次 " + lotAmount + "）");
    }
}
