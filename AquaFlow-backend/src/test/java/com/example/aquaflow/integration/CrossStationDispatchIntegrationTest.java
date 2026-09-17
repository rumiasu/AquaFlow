package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转单 / 外派 / 抢单池的跨站链路。
 *
 * <p>这是本仓库**最容易真丢钱**的一片区域：一单可以「归属站 ≠ 履约站」，于是必须时刻分清
 * <b>钱与票记归属站（{@code orders.station_id}）、实物与库存走履约站（{@code delivery_station_id}）</b>。
 * 外派/抢单只改后者，前者自始至终不变 —— 本类的每一条断言都在守这条线。</p>
 *
 * <p>链路（都走 HTTP，不直接调 service）：</p>
 * <ul>
 *   <li>放抢单池：{@code POST /api/delivery/orders/transfer/{id}/outsource}（{@code targetStationId} 留空）→
 *       履约站与配送员被清空、订单回到待配送；归属站不变。</li>
 *   <li>抢单：目标站 {@code GET /api/delivery/orders/pool} 看得到 → {@code POST .../{id}/claim-pool} →
 *       履约站变成抢单站、状态推到配送中。</li>
 *   <li>召回：{@code POST .../{id}/cancel-dispatch} → 履约站恢复成归属站、配送员清空。</li>
 *   <li>指定外派：{@code .../outsource} 带 {@code targetStationId}（不能是自己）。</li>
 *   <li>站内转单：{@code POST .../transfer/{id}} 直接改派并落 {@code order_transfer} 结构化记录。</li>
 * </ul>
 */
@DisplayName("跨站调度 · 外派 / 抢单池 / 召回 / 站内转单（钱票归归属站，实物走履约站）")
class CrossStationDispatchIntegrationTest extends AbstractIntegrationTest {

    private long stationA;
    private long stationB;
    private long product;
    private long customer;
    private long addr;
    private long mgrA;
    private long mgrB;
    private long driverB;

    private void seed() {
        stationA = createStation("A站");
        stationB = createStation("B站");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(stationA, product, 50, 1, "18.00");
        createInventoryFull(stationB, product, 50, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgrA = createStaff("MA", "STATION_MANAGER", stationA, 1);
        mgrB = createStaff("MB", "STATION_MANAGER", stationB, 1);
        driverB = createStaff("DB", "DELIVERY", stationB, 1);
    }

    private String tokenA() {
        return staffToken(mgrA, "STATION_MANAGER", stationA);
    }

    private String tokenB() {
        return staffToken(mgrB, "STATION_MANAGER", stationB);
    }

    /** A 站的一张待配送订单（履约站也是 A）。 */
    private long pendingOrderAtA() {
        return createOrderFull(customer, addr, stationA, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                "40.00", "0.00", "40.00", false, 2);
    }

    private String deliveryStationOf(long orderId) {
        return jdbc.queryForObject("SELECT IFNULL(delivery_station_id,'NULL') FROM orders WHERE id=?",
                String.class, orderId);
    }

    private long ownerStationOf(long orderId) {
        return longOf("SELECT station_id FROM orders WHERE id=?", orderId);
    }

    private String specialNote(long orderId) {
        String s = jdbc.queryForObject("SELECT IFNULL(special_note,'') FROM orders WHERE id=?", String.class, orderId);
        return s == null ? "" : s;
    }

    private boolean poolContains(String token, long orderId) {
        Api res = get("/api/delivery/orders/pool", token);
        assertTrue(res.isSuccess(), "抢单池列表应可读，实际=" + res);
        for (JsonNode n : res.data()) {
            if (n.path("id").asLong() == orderId) return true;
        }
        return false;
    }

    @Test
    @DisplayName("放池 → 目标站可见并抢单：履约站换人、归属站不变、留痕齐全")
    void outsourceToPoolThenOtherStationClaims() {
        seed();
        long order = pendingOrderAtA();

        Api out = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"reason\":\"本站运力不足\"}");
        assertTrue(out.isSuccess(), "放入抢单池应成功，实际=" + out);

        assertEquals("NULL", deliveryStationOf(order), "放池后履约站必须清空（这是「在池中」的唯一标志）");
        assertEquals(stationA, ownerStationOf(order), "归属站永远不变：钱和票还记 A 站");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "放池不推进状态，仍是待配送");
        assertTrue(specialNote(order).contains("[外派]") && specialNote(order).contains("抢单池"),
                "必须留痕，实际=" + specialNote(order));

        assertFalse(poolContains(tokenA(), order), "归属站自己的池子里不该看到自己的单");
        assertTrue(poolContains(tokenB(), order), "目标站应能在抢单池里看到这一单");

        Api claim = post("/api/delivery/orders/" + order + "/claim-pool", tokenB(),
                "{\"deliveryStaffId\":" + driverB + "}");
        assertTrue(claim.isSuccess(), "抢单应成功，实际=" + claim);

        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "履约站应变为抢单站 B");
        assertEquals(driverB, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "配送员应为指定的 B 站配送员");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order), "抢单即接单，状态推到配送中(2)");
        assertEquals(stationA, ownerStationOf(order), "抢单不得改动归属站");
        assertTrue(specialNote(order).contains("[抢单]"), "必须留痕，实际=" + specialNote(order));
    }

    @Test
    @DisplayName("抢单只能成一次：已出池的单再抢必须被拒")
    void claimPoolIsOnceOnly() {
        seed();
        long stationC = createStation("C站");
        long mgrC = createStaff("MC", "STATION_MANAGER", stationC, 1);

        long order = pendingOrderAtA();
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}").isSuccess());
        assertTrue(post("/api/delivery/orders/" + order + "/claim-pool", tokenB(),
                "{\"deliveryStaffId\":" + driverB + "}").isSuccess(), "B 先抢到");

        Api again = post("/api/delivery/orders/" + order + "/claim-pool",
                staffToken(mgrC, "STATION_MANAGER", stationC), "{\"deliveryStaffId\":" + mgrC + "}");
        assertFalse(again.isSuccess(), "被抢走的单不能再抢，实际=" + again);
        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "履约站不得被二次抢单覆盖");
    }

    @Test
    @DisplayName("取消外派：召回为归属站待配送，配送员清空")
    void cancelDispatchRecallsToOwnerStation() {
        seed();
        long order = pendingOrderAtA();
        assertTrue(post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(), "{}").isSuccess());

        Api back = post("/api/delivery/orders/" + order + "/cancel-dispatch", tokenA(), null);
        assertTrue(back.isSuccess(), "归属站应能取消外派，实际=" + back);

        assertEquals(stationA, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order),
                "召回后履约站恢复为归属站");
        assertEquals("NULL", jdbc.queryForObject("SELECT IFNULL(delivery_staff_id,'NULL') FROM orders WHERE id=?",
                String.class, order), "召回后不该还挂着别人家的配送员");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "召回后回到待配送");
        assertTrue(specialNote(order).contains("[取消外派]"), "必须留痕，实际=" + specialNote(order));
    }

    @Test
    @DisplayName("指定水站外派：履约站换人、状态不变、外派追踪列表可见；不能外派给自己")
    void outsourceToNamedStation() {
        seed();
        long order = pendingOrderAtA();

        Api self = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"targetStationId\":" + stationA + "}");
        assertFalse(self.isSuccess(), "不能外派给自己，实际=" + self);

        Api res = post("/api/delivery/orders/transfer/" + order + "/outsource", tokenA(),
                "{\"targetStationId\":" + stationB + ",\"reason\":\"指定 B 站送\"}");
        assertTrue(res.isSuccess(), "指定外派应成功，实际=" + res);

        assertEquals(stationB, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order));
        assertEquals(stationA, ownerStationOf(order), "归属站不变");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order), "指定外派也不推进状态");
        assertFalse(poolContains(tokenB(), order),
                "指定外派的单**不进抢单池**（履约站已经定了，池子只放 delivery_station_id 为空的单）");

        Api tracking = get("/api/delivery/orders/dispatch-tracking", tokenA());
        assertTrue(tracking.isSuccess(), "外派追踪应可读，实际=" + tracking);
        boolean seen = false;
        for (JsonNode n : tracking.data()) {
            if (n.path("id").asLong() == order) seen = true;
        }
        assertTrue(seen, "归属站的外派追踪列表里应能看到这一单");
    }

    @Test
    @DisplayName("跨站现金单：履约站完成配送并确认收款，但钱/押金/桶账全部记在归属站")
    void crossStationCashFlowsToOwnerStation() {
        seed();
        // 归属站 A、履约站 B（A 指派给 B 去送）
        long order = createOrderCrossStation(customer, addr, stationA, stationB, product,
                2 /* 配送中 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "60.00", "100.00");
        long itemId = createOrderItem(order, product, "桶装水18.9L", 2, "20.00", "30.00", 1);
        createBarrelInTransit(customer, stationA, product, 2, "30.00", order, "PENDING");

        // 谁操作：完成配送与确认收款都只认【履约站】(checkStationOwnership)，归属站连完成都做不了
        Api byOwner = post("/api/delivery/orders/" + order + "/complete", tokenA(), "{}");
        assertFalse(byOwner.isSuccess(), "归属站不是履约站，不得完成配送，实际=" + byOwner);
        assertTrue(byOwner.message().contains("无权操作他站订单"),
                "拒绝原因应指向站别，实际=" + byOwner.message());

        // 履约站 B 完成配送，且现场**未**收款 → 只到已送达，钱还没到手
        Api done = post("/api/delivery/orders/" + order + "/complete", tokenB(),
                "{\"itemReturns\":[{\"orderItemId\":" + itemId + ",\"expected\":2,\"actual\":0,"
                        + "\"reasons\":[{\"key\":\"customer_kept\",\"qty\":2}]}]}");
        assertTrue(done.isSuccess(), "履约站完成配送应成功，实际=" + done);
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order), "未收款 → 停在已送达(3)");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order), "仍是待收款(1)");

        // B 现场收到现金后再确认收款 → 订单闭环；但钱与桶账都记在【归属站】A
        Api pay = post("/api/delivery/orders/" + order + "/confirm-offline-pay", tokenB(), null);
        assertTrue(pay.isSuccess(), "履约站确认线下收款应成功，实际=" + pay);

        assertEquals(4, intOf("SELECT status FROM orders WHERE id=?", order), "收款后订单闭环为已完成");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "应已付款");
        assertEquals(stationA, longOf("SELECT station_id FROM payment_record WHERE order_id=? AND status=2", order),
                "收款流水必须记在【归属站】A（钱是谁的，账就记谁）");
        assertEquals(0, decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, stationA)
                        .compareTo(new BigDecimal("60.00")), "预收押金应入 A 站账户");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_deposit_account WHERE customer_id=? AND station_id=?",
                customer, stationB), "履约站 B 不得出现这个客户的押金账户");

        assertEquals(2, intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND status=1", customer, stationA), "桶权益记在归属站 A");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_lot WHERE customer_id=? AND station_id=?",
                customer, stationB), "履约站 B 不得出现桶权益");
    }

    @Test
    @DisplayName("站内转单：改派配送员并落结构化 order_transfer；配送员只能转自己名下的单")
    void transferToStaffIsRecordedAndScoped() {
        seed();
        long driverA1 = createStaff("DA1", "DELIVERY", stationA, 1);
        long driverA2 = createStaff("DA2", "DELIVERY", stationA, 1);

        long order = pendingOrderAtA();
        String t1 = staffToken(driverA1, "DELIVERY", stationA);
        String t2 = staffToken(driverA2, "DELIVERY", stationA);

        // 先把单派给 DA1
        assertTrue(post("/api/delivery/orders/assign/" + order, tokenA(),
                "{\"deliveryStaffId\":" + driverA1 + "}").isSuccess(), "站长派单应成功");
        assertEquals(driverA1, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order));

        // DA2 想转一张不在自己名下的单 → 拒
        Api notMine = post("/api/delivery/orders/transfer/" + order, t2,
                "{\"deliveryStaffId\":" + driverA2 + ",\"reason\":\"不是我的单\"}");
        assertFalse(notMine.isSuccess(), "配送员不得转让别人名下的单，实际=" + notMine);
        assertEquals(driverA1, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "被拒后配送员不变");

        // DA1 转给 DA2 → 成功、结构化记录落库
        Api ok = post("/api/delivery/orders/transfer/" + order, t1,
                "{\"deliveryStaffId\":" + driverA2 + ",\"reason\":\"我请假\"}");
        assertTrue(ok.isSuccess(), "转让自己的单应成功，实际=" + ok);
        assertEquals(driverA2, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order), "配送员应换成 DA2");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND kind='STAFF' "
                + "AND sub_kind='TRANSFER'", order), "应落一条结构化转单记录（事后可追溯，而不是只写备注）");
        assertTrue(specialNote(order).contains("[转让]"), "必须留痕，实际=" + specialNote(order));

        // 取消转让：待决策的转单记录置为已取消
        Api cancel = post("/api/delivery/orders/transfer/" + order + "/cancel", tokenA(), null);
        assertTrue(cancel.isSuccess(), "取消转让应成功，实际=" + cancel);
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='TRANSFER'", String.class, order),
                "待决策的转单应被置为已取消");
    }

    @Test
    @DisplayName("站长拒单：直接取消走退款；选外派则回到抢单池且状态回到待配送")
    void stationRejectCancelsOrDispatches() {
        seed();
        // 拒单取消：订单终止、库存回补（用待配送现金单，未付款不涉及退款）
        long order1 = pendingOrderAtA();
        Api cancel = post("/api/delivery/orders/" + order1 + "/station-reject", tokenA(),
                "{\"reason\":\"客户地址无法配送\",\"tryDispatch\":false}");
        assertTrue(cancel.isSuccess(), "站长拒单应成功，实际=" + cancel);
        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", order1), "拒单取消应置已取消(5)");

        // 拒单外派：进池、状态仍是待配送、履约站清空
        long order2 = pendingOrderAtA();
        Api dispatch = post("/api/delivery/orders/" + order2 + "/station-reject", tokenA(),
                "{\"reason\":\"本站送不了，外派\",\"tryDispatch\":true}");
        assertTrue(dispatch.isSuccess(), "拒单外派应成功，实际=" + dispatch);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order2), "外派支线不取消订单");
        assertEquals("NULL", deliveryStationOf(order2), "外派支线应把订单放回抢单池");
        assertTrue(poolContains(tokenB(), order2), "B 站应能在池里看到它");
    }
}
