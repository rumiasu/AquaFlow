package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条相邻的「调解」支线（矩阵 C2，2026-09-16 前零覆盖）：
 * <ul>
 *   <li><b>退回站长</b>（站内，{@code orders/return/**}）：配送员把订单退回给站长，站长同意/拒绝。</li>
 *   <li><b>指定退回</b>（站间，{@code orders/{id}/directed-return/**}）：被指定履约的水站把外派单
 *       退回原归属站，由归属站站长决定同意/拒绝。</li>
 * </ul>
 *
 * <p>两者共用一条最容易被写坏的不变量 —— <b>[AQ-016]「退回待审期间不许清空配送员」</b>：
 * 一旦在申请阶段就把 delivery_staff_id 清掉，站长点「拒绝」时状态要回到「配送中」，
 * 却已经没有配送员可恢复 → 订单卡成「配送中但无人可送」的孤儿单。
 * 所以本类专门断言：申请后配送员仍在，只有同意才清空；拒绝必须能原样回到配送中。</p>
 */
class DirectedReturnAndReturnToStationIntegrationTest extends AbstractIntegrationTest {

    /** 把订单摆成「配送中 + 已分配配送员」的现场（createOrderFull 不写 delivery_staff_id）。 */
    private void assignDelivery(long orderId, long staffId, long deliveryStationId) {
        jdbc.update("UPDATE orders SET delivery_staff_id=?, status=2, delivery_station_id=? WHERE id=?",
                staffId, deliveryStationId, orderId);
    }

    @Test
    @DisplayName("退回站长：申请→同意清空配送员；拒绝→回到配送中且配送员还在")
    void returnToStationApproveAndReject() {
        long station = createStation("退回站");
        long manager = createStaff("退回站长", "STATION_MANAGER", station, 1);
        long delivery = createStaff("退回配送员", "DELIVERY", station, 1);
        long customer = createCustomer("退回客户", "return-openid");
        long product = createProduct("退回水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "退回地址");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String del = staffToken(delivery, "DELIVERY", station);

        // ---- 情形 1：同意退回 → 转成真正待分配（清空配送员） ----
        long order1 = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "30.00", "40.00", true, 2);
        assignDelivery(order1, delivery, station);

        Api applied = post("/api/delivery/orders/return/" + order1, del, "{\"reason\":\"客户改约\"}");
        assertEquals(0, applied.code(), "配送员退回站长: " + applied);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order1), "退回后应转为待配送(1)");
        assertEquals(delivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order1),
                "[AQ-016] 待审期间必须保留配送员，否则拒绝时无配送员可恢复");
        assertTrue(jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order1)
                .contains("[退回站长]"), "应留下退回标记");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND kind='STAFF' "
                + "AND sub_kind='RETURN_STATION' AND status='PENDING'", order1), "[AQ-015] 应有结构化转单记录");

        assertEquals(0, post("/api/delivery/orders/return/" + order1 + "/approve", mgr, null).code());
        assertNull(jdbc.queryForObject("SELECT delivery_staff_id FROM orders WHERE id=?", Object.class, order1),
                "同意退回才清空配送员");
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? "
                + "AND sub_kind='RETURN_STATION'", String.class, order1));

        // ---- 情形 2：拒绝退回 → 回到配送中，原配送员继续送 ----
        long order2 = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "30.00", "40.00", true, 2);
        assignDelivery(order2, delivery, station);
        assertEquals(0, post("/api/delivery/orders/return/" + order2, del, "{\"reason\":\"临时有事\"}").code());
        Api rejected = post("/api/delivery/orders/return/" + order2 + "/reject", mgr, null);
        assertEquals(0, rejected.code(), "站长拒绝退回: " + rejected);
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order2), "拒绝后应回到配送中(2)");
        assertEquals(delivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order2),
                "拒绝后退回原配送员手上，单子不能没人管");
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? "
                + "AND sub_kind='RETURN_STATION'", String.class, order2));

        // ---- 守卫：只能退自己名下的单；拒绝时必须有配送员 ----
        long otherDelivery = createStaff("别的配送员", "DELIVERY", station, 1);
        long order3 = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "30.00", "40.00", true, 2);
        assignDelivery(order3, delivery, station);
        assertNotEquals(0, post("/api/delivery/orders/return/" + order3,
                staffToken(otherDelivery, "DELIVERY", station), "{\"reason\":\"抢单\"}").code(),
                "配送员不得退回别人的单");

        long order4 = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        assertEquals(0, post("/api/delivery/orders/return/" + order4, mgr, "{\"reason\":\"站长退回\"}").code(),
                "待配送(无配送员)的订单也能挂退回标记");
        assertNotEquals(0, post("/api/delivery/orders/return/" + order4 + "/reject", mgr, null).code(),
                "[AQ-016] 无配送员时拒绝退回必须被拦，否则订单会卡成配送中但无人可送");

        // ---- 守卫：只能操作本站履约的订单 ----
        long otherStation = createStation("别的水站");
        long otherManager = createStaff("别站站长", "STATION_MANAGER", otherStation, 1);
        assertNotEquals(0, post("/api/delivery/orders/return/" + order3,
                staffToken(otherManager, "STATION_MANAGER", otherStation), "{\"reason\":\"越权\"}").code(),
                "他站不得退回本站订单");
        assertNotEquals(0, post("/api/delivery/orders/return/" + order3 + "/approve",
                staffToken(otherManager, "STATION_MANAGER", otherStation), null).code(),
                "他站不得审批本站退回");
    }

    @Test
    @DisplayName("指定退回：目标站申请→归属站同意/拒绝，履约站与配送员的保留规则各不相同")
    void directedReturnApproveAndReject() {
        long ownerStation = createStation("归属站");
        long fulfillStation = createStation("履约站");
        long ownerManager = createStaff("归属站站长", "STATION_MANAGER", ownerStation, 1);
        long fulfillManager = createStaff("履约站站长", "STATION_MANAGER", fulfillStation, 1);
        long fulfillDelivery = createStaff("履约站配送员", "DELIVERY", fulfillStation, 1);
        long customer = createCustomer("指定退回客户", "directed-openid");
        long product = createProduct("指定退回水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "指定退回地址");

        String mgrOwner = staffToken(ownerManager, "STATION_MANAGER", ownerStation);
        String mgrFulfill = staffToken(fulfillManager, "STATION_MANAGER", fulfillStation);

        // ---- 情形 1：同意 → 履约站改回归属站、配送员清空、订单回待分配 ----
        long order1 = createOrderCrossStation(customer, address, ownerStation, fulfillStation, product,
                2, 2, 2, "10.00", "30.00", "40.00");
        assignDelivery(order1, fulfillDelivery, fulfillStation);

        Api applied = post("/api/delivery/orders/" + order1 + "/directed-return", mgrFulfill, null);
        assertEquals(0, applied.code(), "目标站申请退回: " + applied);
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order1), "进入转单中：状态回到待配送(1)");
        assertEquals(fulfillStation, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order1),
                "[关键] 申请阶段不得改履约站 —— 拒绝时要靠它还原");
        assertEquals(fulfillDelivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order1),
                "[关键] 申请阶段不得清空配送员");
        assertTrue(jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order1)
                .contains("[指定退回待确认]"));
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND kind='DIRECTED' "
                + "AND sub_kind='DIRECTED_RETURN' AND status='PENDING'", order1));

        // 归属站能在"待我确认"列表里看到它
        Api pending = get("/api/delivery/orders/directed-returns", mgrOwner);
        assertEquals(0, pending.code(), "归属站待确认列表: " + pending);
        assertTrue(pending.data().toString().contains("\"id\":" + order1), "待确认列表应含该订单");

        assertEquals(0, post("/api/delivery/orders/" + order1 + "/directed-return/approve", mgrOwner, null).code());
        assertEquals(ownerStation, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order1),
                "同意后履约站改回归属站");
        assertNull(jdbc.queryForObject("SELECT delivery_staff_id FROM orders WHERE id=?", Object.class, order1));
        assertTrue(jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order1)
                .contains("[指定退回-同意]"));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? "
                + "AND kind='DIRECTED'", String.class, order1));
        // 幂等/CAS：标记已被替换，再点一次必须失败，而不是把已经回到归属站的单再改一遍
        assertNotEquals(0, post("/api/delivery/orders/" + order1 + "/directed-return/approve", mgrOwner, null).code(),
                "重复同意必须被 CAS 守卫挡住");

        // ---- 情形 2：拒绝 → 回到配送中，履约站与配送员原样保留 ----
        long order2 = createOrderCrossStation(customer, address, ownerStation, fulfillStation, product,
                2, 2, 2, "10.00", "30.00", "40.00");
        assignDelivery(order2, fulfillDelivery, fulfillStation);
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/directed-return", mgrFulfill, null).code());
        Api rejected = post("/api/delivery/orders/" + order2 + "/directed-return/reject", mgrOwner, null);
        assertEquals(0, rejected.code(), "归属站拒绝退回: " + rejected);
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order2), "拒绝后回到配送中(2)");
        assertEquals(fulfillStation, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order2));
        assertEquals(fulfillDelivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order2),
                "拒绝后仍由原履约站的原配送员送完");
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? "
                + "AND kind='DIRECTED'", String.class, order2));

        // ---- 守卫 ----
        long order3 = createOrderCrossStation(customer, address, ownerStation, fulfillStation, product,
                2, 2, 2, "10.00", "30.00", "40.00");
        assignDelivery(order3, fulfillDelivery, fulfillStation);
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return", mgrOwner, null).code(),
                "只有目标(履约)站可以申请退回，归属站不行");
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve", mgrFulfill, null).code(),
                "没有待确认标记时不得同意");
        assertEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return", mgrFulfill, null).code());
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve", mgrFulfill, null).code(),
                "只有原归属站可以同意退回");
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve",
                staffToken(fulfillDelivery, "DELIVERY", fulfillStation), null).code(),
                "配送员不得审批站间退回（且角色不符应被拒）");
    }
}
