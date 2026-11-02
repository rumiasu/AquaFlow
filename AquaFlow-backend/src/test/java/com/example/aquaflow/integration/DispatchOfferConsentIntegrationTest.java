package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-03 报价确认（offer consent）的真实 HTTP/MySQL 回归。
 *
 * <p>被测判据只有一条：<b>接受一份 OFFERED 报价时，必须出示接收站自己看过的那一轮 token</b>。
 * 因此每个用例都先取「界面上看到的 token」，再让报价/路线/轮次发生变化，最后证明：
 * 旧 token 被拒且<b>没有任何落库副作用</b>，新 token 通过且副作用与报价一致。</p>
 *
 * <p>本类刻意使用非桶装（category=2、deposit=0）商品：押金/桶权益单按产品裁定<b>禁止入池</b>，
 * 用它才能覆盖抢单池入口而不改动那条规则。桶条款变更另用一条真实的桶权益单覆盖。</p>
 */
@TestPropertySource(properties = "aquaflow.barrel.independent-rights-enabled=true")
class DispatchOfferConsentIntegrationTest extends AbstractIntegrationTest {

    private long a, b, c, ma, mb, mc, db, dc, product, customer, address;
    private String ca, ta, tb, tc;

    private void seed() {
        a = createStation("报价甲站"); b = createStation("报价乙站"); c = createStation("报价丙站");
        ma = createStaff("报价甲站长", "STATION_MANAGER", a, 1);
        mb = createStaff("报价乙站长", "STATION_MANAGER", b, 1);
        mc = createStaff("报价丙站长", "STATION_MANAGER", c, 1);
        db = createStaff("报价乙配送", "DELIVERY", b, 1);
        dc = createStaff("报价丙配送", "DELIVERY", c, 1);
        product = createProduct("报价瓶装水", 2, "40.00", "0.00", 0, "0.00");
        createInventoryFull(a, product, 20, 0, "0.00");
        createInventoryFull(b, product, 20, 0, "0.00");
        createInventoryFull(c, product, 20, 0, "0.00");
        customer = createCustomer("报价合成客户", "offer-consent-only");
        address = createAddress(customer, "报价合成门牌");
        createCustomerStationConfig(customer, a, 1);
        ca = customerToken(customer);
        ta = staffToken(ma, "STATION_MANAGER", a);
        tb = staffToken(mb, "STATION_MANAGER", b);
        tc = staffToken(mc, "STATION_MANAGER", c);
    }

    /** 真实下单（会生成 order_item 与 inventory_reservation 凭据，便于断言库存副作用）。 */
    private long order(String key) {
        Api created = post("/api/orders/create", ca, "{\"stationId\":" + a + ",\"addressId\":" + address
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\",\"items\":[{\"productId\":" + product
                + ",\"quantity\":1}]}");
        assertEquals(0, created.code(), created.toString());
        long id = created.data().path("orderId").asLong();
        assertTrue(id > 0, created.toString());
        return id;
    }

    private Api outsource(long id, Long target) {
        return post("/api/delivery/orders/transfer/" + id + "/outsource", ta,
                target == null ? "{}" : "{\"targetStationId\":" + target + "}");
    }

    /** 取「接收站界面上看到的那一份报价 token」。 */
    private String viewedToken(long id, String stationToken) {
        Api view = get("/api/manager/dispatch-agreements/" + id, stationToken);
        assertEquals(0, view.code(), view.toString());
        String token = view.data().path("quoteToken").asText();
        assertTrue(token.matches("[a-f0-9]{64}"), view.toString());
        return token;
    }

    private Api quote(long id, String amount, String note) {
        return put("/api/manager/dispatch-agreements/" + id, ta, "{\"serviceAmount\":" + amount
                + ",\"barrelMode\":\"RETURN_EMPTY\",\"barrelAmount\":0,\"note\":\"" + note + "\"}");
    }

    private String agreementStatus(long id) {
        return jdbc.queryForObject("select status from dispatch_agreement where order_id=?", String.class, id);
    }

    private void assertNothingAccepted(long id, long reservedAt) {
        assertEquals(1, intOf("select status from orders where id=?", id), "被拒的确认不能推进订单状态");
        assertEquals(0, intOf("select count(*) from orders where id=? and delivery_staff_id is not null", id),
                "被拒的确认不能留下配送员");
        assertEquals(0, intOf("select count(*) from dispatch_agreement where order_id=? and status='ACCEPTED'", id),
                "被拒的确认不能把协议置为 ACCEPTED");
        assertEquals(reservedAt, longOf("select station_id from inventory_reservation where order_id=? and status=1", id),
                "被拒的确认不能把库存凭据搬走");
        assertEquals(20, intOf("select quantity from inventory where station_id=? and product_id=?", a, product));
        assertEquals(20, intOf("select quantity from inventory where station_id=? and product_id=?", b, product));
        assertEquals(0, intOf("select count(*) from barrel_record where related_order_id=?", id));
        assertEquals(0, intOf("select count(*) from ticket_record where order_id=?", id));
        assertEquals(0, intOf("select count(*) from payment_record where order_id=? and status=2", id),
                "被拒的确认不能产生已付款凭据");
    }

    /* ==================== 三条入口：旧 token -> 新 token ==================== */

    @Test
    @DisplayName("直接接单：改价后旧 token 被拒，新 token 才能接受")
    void directedAcceptRejectsStaleTokenAndAcceptsTheDisplayedOne() {
        seed();
        long id = order("consent-accept");
        assertTrue(outsource(id, b).isSuccess());
        String displayed = viewedToken(id, tb);
        assertTrue(quote(id, "35.00", "改价后的报价").isSuccess());

        long audits = longOf("select count(*) from audit_log");
        Api stale = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertTrue(stale.message().contains("报价"), stale.toString());
        assertEquals("OFFERED", agreementStatus(id));
        assertEquals(0, new BigDecimal("35.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
        assertEquals(audits, longOf("select count(*) from audit_log"), "被拒的确认不能留下 ACCEPT 审计");
        assertNothingAccepted(id, b);

        String current = viewedToken(id, tb);
        assertNotEquals(displayed, current, "改价必须换一轮 token");
        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + current + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
        assertEquals(mb, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals(b, longOf("select target_station_id from dispatch_agreement where order_id=?", id));
        assertEquals(0, new BigDecimal("35.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
    }

    @Test
    @DisplayName("接收站分配：改价后旧 token 被拒，新 token 冻结报价")
    void directedAssignRejectsStaleTokenAndFreezesTheDisplayedQuote() {
        seed();
        long id = order("consent-assign");
        assertTrue(outsource(id, b).isSuccess());
        String displayed = viewedToken(id, tb);
        assertTrue(quote(id, "35.00", "改价后的报价").isSuccess());

        Api stale = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertEquals("OFFERED", agreementStatus(id));
        assertNothingAccepted(id, b);
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id), "被拒时履约站仍是目标站");

        String current = viewedToken(id, tb);
        Api assigned = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + current + "\"}");
        assertEquals(0, assigned.code(), assigned.toString());
        assertEquals(1, intOf("select status from orders where id=?", id), "分配只指派，不改状态");
        assertEquals(db, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals(b, longOf("select target_station_id from dispatch_agreement where order_id=?", id));
        assertEquals(0, new BigDecimal("35.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
    }

    @Test
    @DisplayName("抢单池：改价后旧 token 被拒，新 token 才能抢单")
    void poolClaimRejectsStaleTokenAndAcceptsTheViewedOne() {
        seed();
        long id = order("consent-pool");
        assertTrue(outsource(id, null).isSuccess());
        String displayed = viewedToken(id, tb);
        assertTrue(quote(id, "33.00", "改价后的报价").isSuccess());

        Api stale = post("/api/delivery/orders/" + id + "/claim-pool-quoted", tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertEquals("OFFERED", agreementStatus(id));
        assertNothingAccepted(id, a);
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_station_id is null", id), "被拒时仍在池中");

        String current = viewedToken(id, tb);
        Api claimed = post("/api/delivery/orders/" + id + "/claim-pool-quoted", tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + current + "\"}");
        assertEquals(0, claimed.code(), claimed.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
        assertEquals(db, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id));
        assertEquals(b, longOf("select settle_station_id from orders where id=?", id));
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals(b, longOf("select target_station_id from dispatch_agreement where order_id=?", id));
        assertEquals(0, new BigDecimal("33.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
        assertEquals(b, longOf("select station_id from inventory_reservation where order_id=? and status=1", id));
        assertEquals(20, intOf("select quantity from inventory where station_id=? and product_id=?", a, product), "搬凭据不是扣实物");
        assertEquals(20, intOf("select quantity from inventory where station_id=? and product_id=?", b, product));
        assertEquals(0, intOf("select count(*) from ticket_record where order_id=?", id));
    }

    /* ==================== 绑定维度：订单 / 站点 / 轮次 ==================== */

    @Test
    @DisplayName("token 绑定订单、接收站与轮次：缺失/错单/错站都拒绝且无副作用")
    void tokensAreBoundToTheirOrderStationAndRound() {
        seed();
        long first = order("consent-bind-1");
        long second = order("consent-bind-2");
        assertTrue(outsource(first, b).isSuccess());
        assertTrue(outsource(second, b).isSuccess());
        String firstToken = viewedToken(first, tb);
        String secondToken = viewedToken(second, tb);
        assertNotEquals(firstToken, secondToken, "不同订单的 token 不能相同");

        Api missing = post("/api/delivery/orders/" + first + "/accept-quoted", tb, "{}");
        assertEquals(1, missing.code(), missing.toString());
        assertNothingAccepted(first, b);

        Api wrongOrder = post("/api/delivery/orders/" + first + "/accept-quoted", tb, "{\"quoteToken\":\"" + secondToken + "\"}");
        assertEquals(1, wrongOrder.code(), wrongOrder.toString());
        assertNothingAccepted(first, b);

        Api missingAssign = post("/api/delivery/orders/assign-quoted/" + first, tb, "{\"deliveryStaffId\":" + db + "}");
        assertEquals(1, missingAssign.code(), missingAssign.toString());
        assertNothingAccepted(first, b);

        long pooled = order("consent-bind-pool");
        assertTrue(outsource(pooled, null).isSuccess());
        String seenByB = viewedToken(pooled, tb);
        String seenByC = viewedToken(pooled, tc);
        assertNotEquals(seenByB, seenByC, "池中报价的 token 必须绑定接收站");
        Api wrongStation = post("/api/delivery/orders/" + pooled + "/claim-pool-quoted", tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + seenByC + "\"}");
        assertEquals(1, wrongStation.code(), wrongStation.toString());
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_station_id is null", pooled));
        assertEquals("OFFERED", agreementStatus(pooled));
        assertEquals(a, longOf("select station_id from inventory_reservation where order_id=? and status=1", pooled));

        Api missingClaim = post("/api/delivery/orders/" + pooled + "/claim-pool-quoted", tb, "{\"deliveryStaffId\":" + db + "}");
        assertEquals(1, missingClaim.code(), missingClaim.toString());
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_station_id is null", pooled));
    }

    /* ==================== 轮次：召回 / 重新报价 / A->B->A ==================== */

    @Test
    @DisplayName("召回换轮次：A->B->A->B 后旧 token 不能复用")
    void recallInvalidatesTheDisplayedOfferAndAReOfferCannotReuseIt() {
        seed();
        long id = order("consent-recall");
        assertTrue(outsource(id, b).isSuccess());
        String beforeRecall = viewedToken(id, tb);

        assertTrue(post("/api/delivery/orders/" + id + "/cancel-dispatch", ta, "{}").isSuccess());
        assertEquals(a, longOf("select delivery_station_id from orders where id=?", id));
        assertEquals("OFFERED", agreementStatus(id));
        assertNull(jdbc.queryForObject("select target_station_id from dispatch_agreement where order_id=?", Long.class, id));

        assertTrue(outsource(id, b).isSuccess());
        assertTrue(longOf("select offer_version from dispatch_agreement where order_id=?", id) >= 3,
                "召回与重新外派都必须推进轮次");
        String afterRecall = viewedToken(id, tb);
        assertNotEquals(beforeRecall, afterRecall, "召回后重新报价不能复用旧 token");

        Api stale = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + beforeRecall + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertNothingAccepted(id, b);

        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + afterRecall + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
    }

    @Test
    @DisplayName("同样的报价重复提交保持同一 token（不是每点一次就换一轮）")
    void identicalQuoteKeepsTheSameDisplayedToken() {
        seed();
        long id = order("consent-stable");
        assertTrue(outsource(id, b).isSuccess());
        assertTrue(quote(id, "40.00", "重复报价口径").isSuccess());
        String once = viewedToken(id, tb);
        assertTrue(quote(id, "40.00", "重复报价口径").isSuccess());
        assertEquals(once, viewedToken(id, tb), "金额/桶方案/备注都没变时不得推进轮次");
        assertEquals(once, viewedToken(id, tb));
        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + once + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
    }

    /* ==================== 零报价 / 冻结 / 已接受后的司机接单 ==================== */

    @Test
    @DisplayName("零元报价仍需出示 token，不能被当成「没有报价」")
    void aZeroQuoteStillRequiresTheDisplayedToken() {
        seed();
        long id = order("consent-zero");
        assertTrue(outsource(id, b).isSuccess());
        assertTrue(quote(id, "0.00", "双方明确的零报酬报价").isSuccess());
        assertEquals(0, BigDecimal.ZERO.compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));

        String displayed = viewedToken(id, tb);
        Api missing = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{}");
        assertEquals(1, missing.code(), missing.toString());
        assertEquals("OFFERED", agreementStatus(id));
        assertNothingAccepted(id, b);

        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
    }

    @Test
    @DisplayName("接受后报价冻结，已接受的司机仍走旧接单命令继续履约")
    void acceptedOfferIsFrozenAndTheAssignedDriverContinuesOnTheLegacyRoute() {
        seed();
        long id = order("consent-freeze");
        assertTrue(outsource(id, b).isSuccess());
        String displayed = viewedToken(id, tb);
        Api assigned = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(0, assigned.code(), assigned.toString());
        long version = longOf("select offer_version from dispatch_agreement where order_id=?", id);

        Api reprice = quote(id, "99.00", "接受后改价");
        assertEquals(1, reprice.code(), reprice.toString());
        assertEquals(0, new BigDecimal("40.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
        assertEquals(version, longOf("select offer_version from dispatch_agreement where order_id=?", id));
        assertFalse(get("/api/manager/dispatch-agreements/" + id, ta).data().path("canEditQuote").asBoolean(true),
                "接受后归属站不应再能改价");

        Api driver = post("/api/delivery/orders/" + id + "/accept", staffToken(db, "DELIVERY", b), "{}");
        assertEquals(0, driver.code(), driver.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
        assertEquals(db, longOf("select delivery_staff_id from orders where id=?", id));
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals(0, new BigDecimal("40.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));
    }

    /* ==================== 并发：两个站抢同一张池中单 ==================== */

    @Test
    @DisplayName("两个站同时抢同一张池中单：只有一个能确认报价并抢到")
    void twoStationsRacingForTheSamePoolOfferCannotBothConsent() throws Exception {
        seed();
        long id = order("consent-race");
        assertTrue(outsource(id, null).isSuccess());
        String seenByB = viewedToken(id, tb);
        String seenByC = viewedToken(id, tc);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Api> first = pool.submit(() -> {
                start.await();
                return post("/api/delivery/orders/" + id + "/claim-pool-quoted", tb,
                        "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + seenByB + "\"}");
            });
            Future<Api> second = pool.submit(() -> {
                start.await();
                return post("/api/delivery/orders/" + id + "/claim-pool-quoted", tc,
                        "{\"deliveryStaffId\":" + dc + ",\"quoteToken\":\"" + seenByC + "\"}");
            });
            start.countDown();
            List<Api> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Api::isSuccess).count(), results.toString());
            assertTrue(results.stream().allMatch(r -> r.code() == 0 || r.code() == 1), results.toString());

            assertEquals(2, intOf("select status from orders where id=?", id));
            assertEquals(1, longOf("select count(*) from dispatch_agreement where order_id=? and status='ACCEPTED'", id));
            long winner = longOf("select delivery_station_id from orders where id=?", id);
            assertTrue(winner == b || winner == c, "赢家必须是其中一个接收站");
            assertEquals(winner, longOf("select target_station_id from dispatch_agreement where order_id=?", id));
            assertEquals(winner == b ? db : dc, longOf("select delivery_staff_id from orders where id=?", id));
            assertEquals(winner, longOf("select settle_station_id from orders where id=?", id));
            assertEquals(winner, longOf("select station_id from inventory_reservation where order_id=? and status=1", id),
                    "库存凭据只能落到赢家站");
            assertEquals(0, intOf("select count(*) from ticket_record where order_id=?", id));
        } finally {
            pool.shutdownNow();
        }
    }

    /* ==================== 桶条款变更（真实桶权益单） ==================== */

    @Test
    @DisplayName("桶方案/桶折款变化同样换轮次：旧 token 不能接受新条款")
    void changedBarrelTermsInvalidateTheDisplayedToken() {
        seed();
        long barrel = createProduct("报价桶装水", 1, "20.00", "30.00", 1, "8.00");
        createInventoryFull(a, barrel, 20, 1, "8.00");
        createInventoryFull(b, barrel, 20, 1, "8.00");
        String purchase = "{\"stationId\":" + a + ",\"productId\":" + barrel
                + ",\"quantity\":1,\"paymentMethod\":2,\"idempotencyKey\":\"consent-barrel-right\"}";
        Api bought = post("/api/barrel-rights/purchase", ca, purchase);
        assertEquals(0, bought.code(), bought.toString());
        assertEquals(0, put("/api/payments/" + bought.data().path("paymentId").asLong() + "/confirm", ta, null).code());

        Api created = post("/api/orders/create", ca, "{\"stationId\":" + a + ",\"addressId\":" + address
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"consent-barrel-order\",\"items\":[{\"productId\":"
                + barrel + ",\"quantity\":1}]}");
        assertEquals(0, created.code(), created.toString());
        long id = created.data().path("orderId").asLong();

        Api sent = post("/api/delivery/orders/" + id + "/dispatch", ta,
                "{\"targetStationId\":" + b + ",\"riskAcknowledged\":true}");
        assertEquals(0, sent.code(), sent.toString());
        assertEquals(1, intOf("select net_barrels from dispatch_agreement where order_id=?", id), "本单净领 1 个同型空桶");
        String displayed = viewedToken(id, tb);

        Api requoted = put("/api/manager/dispatch-agreements/" + id, ta,
                "{\"serviceAmount\":40.00,\"barrelMode\":\"SETTLE_BARREL\",\"barrelAmount\":30,\"note\":\"桶折款另算\"}");
        assertEquals(0, requoted.code(), requoted.toString());
        assertEquals("SETTLE_BARREL", jdbc.queryForObject("select barrel_mode from dispatch_agreement where order_id=?", String.class, id));
        assertEquals(0, new BigDecimal("30.00").compareTo(decimalOf("select barrel_amount from dispatch_agreement where order_id=?", id)));

        Api stale = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"riskAcknowledged\":true,\"quoteToken\":\"" + displayed + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertEquals("OFFERED", agreementStatus(id));
        assertEquals(0, intOf("select count(*) from orders where id=? and delivery_staff_id is not null", id));
        assertEquals(1, intOf("select status from orders where id=?", id));

        String current = viewedToken(id, tb);
        assertNotEquals(displayed, current);
        Api assigned = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"riskAcknowledged\":true,\"quoteToken\":\"" + current + "\"}");
        assertEquals(0, assigned.code(), assigned.toString());
        assertEquals("ACCEPTED", agreementStatus(id));
        assertEquals("SETTLE_BARREL", jdbc.queryForObject("select barrel_mode from dispatch_agreement where order_id=?", String.class, id));
    }

    /* ==================== 开关关闭 / 缺协议行（fail closed） ==================== */

    @Test
    @DisplayName("模型已启用但协议行缺失：三条接受入口都 fail closed，且不留下任何副作用")
    void enabledModelFailsClosedWhenTheAgreementRowIsMissing() {
        seed();
        // 合成历史形态：跨站单存在、但没有任何 dispatch_agreement 行（不是重写真实历史）。
        long id = createOrderCrossStation(customer, address, a, b, product, 1, 1, 2, "40.00", "0.00", "40.00");
        assertEquals(0, intOf("select count(*) from dispatch_agreement where order_id=?", id));

        long audits = longOf("select count(*) from audit_log");
        String arbitrary = "a".repeat(64);
        Api legacy = post("/api/delivery/orders/" + id + "/accept", tb, "{}");
        assertEquals(1, legacy.code(), legacy.toString());
        assertTrue(legacy.message().contains("报价"), legacy.toString());
        Api quoted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + arbitrary + "\"}");
        assertEquals(1, quoted.code(), quoted.toString());
        Api assign = post("/api/delivery/orders/assign-quoted/" + id, tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + arbitrary + "\"}");
        assertEquals(1, assign.code(), assign.toString());

        assertEquals(1, intOf("select status from orders where id=?", id));
        assertEquals(0, intOf("select count(*) from orders where id=? and delivery_staff_id is not null", id));
        assertEquals(0, intOf("select count(*) from dispatch_agreement where order_id=?", id));
        assertEquals(audits, longOf("select count(*) from audit_log"));
    }

    /* ==================== ABA：同一维度回到旧值也不能复用旧 token ==================== */

    @Test
    @DisplayName("价格 40->5->40：回到原价也必须换一轮，旧 token 不能复用")
    void priceRoundTripDoesNotReviveTheOldToken() {
        seed();
        long id = order("aba-price");
        assertTrue(outsource(id, b).isSuccess());
        long version = longOf("select offer_version from dispatch_agreement where order_id=?", id);
        String original = viewedToken(id, tb);
        assertTrue(quote(id, "5.00", "ABA 降价").isSuccess());
        assertNotEquals(original, viewedToken(id, tb));
        assertTrue(quote(id, "40.00", "ABA 恢复原价").isSuccess());
        String restored = viewedToken(id, tb);
        assertEquals(version + 2, longOf("select offer_version from dispatch_agreement where order_id=?", id));
        assertNotEquals(original, restored, "40->5->40 回到原价也不能复用第一次的 token");
        assertEquals(0, new BigDecimal("40.00").compareTo(decimalOf("select service_amount from dispatch_agreement where order_id=?", id)));

        Api stale = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + original + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertNothingAccepted(id, b);
        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + restored + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
    }

    @Test
    @DisplayName("定向目标 B->C->B：回到 B 也必须换一轮")
    void directedTargetRoundTripDoesNotReviveTheOldToken() {
        seed();
        long id = order("aba-target");
        assertTrue(outsource(id, b).isSuccess());
        String firstAtB = viewedToken(id, tb);
        assertTrue(outsource(id, c).isSuccess());
        assertEquals(c, longOf("select delivery_station_id from orders where id=?", id));
        assertNotEquals(firstAtB, viewedToken(id, tc));
        assertTrue(outsource(id, b).isSuccess());
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id));
        String secondAtB = viewedToken(id, tb);
        assertNotEquals(firstAtB, secondAtB, "B->C->B 不能复用 B 第一次的 token");

        Api stale = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + firstAtB + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertNothingAccepted(id, b);
        Api accepted = post("/api/delivery/orders/" + id + "/accept-quoted", tb, "{\"quoteToken\":\"" + secondAtB + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
    }

    @Test
    @DisplayName("池 -> 定向 -> 池：回到池中也必须换一轮")
    void poolToDirectedAndBackDoesNotReviveTheOldToken() {
        seed();
        long id = order("aba-pool");
        assertTrue(outsource(id, null).isSuccess());
        String firstInPool = viewedToken(id, tb);
        assertTrue(outsource(id, b).isSuccess());
        assertNotEquals(firstInPool, viewedToken(id, tb));
        assertTrue(outsource(id, null).isSuccess());
        assertEquals(1, intOf("select count(*) from orders where id=? and delivery_station_id is null", id));
        String secondInPool = viewedToken(id, tb);
        assertNotEquals(firstInPool, secondInPool, "池->定向->池 不能复用第一次的池中 token");

        Api stale = post("/api/delivery/orders/" + id + "/claim-pool-quoted", tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + firstInPool + "\"}");
        assertEquals(1, stale.code(), stale.toString());
        assertNothingAccepted(id, a);
        Api claimed = post("/api/delivery/orders/" + id + "/claim-pool-quoted", tb,
                "{\"deliveryStaffId\":" + db + ",\"quoteToken\":\"" + secondInPool + "\"}");
        assertEquals(0, claimed.code(), claimed.toString());
        assertEquals(2, intOf("select status from orders where id=?", id));
        assertEquals(b, longOf("select delivery_station_id from orders where id=?", id));
    }

    /* ==================== 同目标重复路由 = 同一轮；真实改目标才换轮 ==================== */

    @Test
    @DisplayName("同一目标/同一池重复路由保持轮次与 token；真实改目标仍失效")
    void repeatedIdenticalRoutingKeepsTheSameDisplayedToken() {
        seed();
        long directed = order("idem-directed");
        assertTrue(outsource(directed, b).isSuccess());
        long directedVersion = longOf("select offer_version from dispatch_agreement where order_id=?", directed);
        String directedToken = viewedToken(directed, tb);
        assertTrue(outsource(directed, b).isSuccess());
        assertEquals(directedVersion, longOf("select offer_version from dispatch_agreement where order_id=?", directed),
                "同一目标重复外派不得推进轮次");
        assertEquals(directedToken, viewedToken(directed, tb), "同一目标重复外派不得换 token");
        Api accepted = post("/api/delivery/orders/" + directed + "/accept-quoted", tb, "{\"quoteToken\":\"" + directedToken + "\"}");
        assertEquals(0, accepted.code(), accepted.toString());

        long pooled = order("idem-pool");
        assertTrue(outsource(pooled, null).isSuccess());
        long poolVersion = longOf("select offer_version from dispatch_agreement where order_id=?", pooled);
        String poolToken = viewedToken(pooled, tb);
        assertTrue(outsource(pooled, null).isSuccess());
        assertEquals(poolVersion, longOf("select offer_version from dispatch_agreement where order_id=?", pooled),
                "重复放入同一池不得推进轮次");
        assertEquals(poolToken, viewedToken(pooled, tb), "重复放入同一池不得换 token");
        assertTrue(outsource(pooled, b).isSuccess());
        assertTrue(longOf("select offer_version from dispatch_agreement where order_id=?", pooled) > poolVersion,
                "真实改目标必须推进轮次");
        assertNotEquals(poolToken, viewedToken(pooled, tb), "真实改目标必须换 token");
    }
}
