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
 * 一旦在申请阶段就把 delivery_staff_id 清掉，站长点「拒绝」时就没有配送员可恢复。
 * 2026-11-04 起，站内拒绝还要恢复<b>申请前的主状态</b>：未接单回待配送，已接单才回配送中；
 * 申请后配送员又接单的，拒绝只撤申请、不能把已开始的配送倒滚。历史申请证据不足时拒绝猜测。</p>
 */
class DirectedReturnAndReturnToStationIntegrationTest extends AbstractIntegrationTest {

    /** 把订单摆成「配送中 + 已分配配送员」的现场（createOrderFull 不写 delivery_staff_id）。 */
    private void assignDelivery(long orderId, long staffId, long deliveryStationId) {
        jdbc.update("UPDATE orders SET delivery_staff_id=?, status=2, delivery_station_id=? WHERE id=?",
                staffId, deliveryStationId, orderId);
    }

    private String decisionBody(long orderId) {
        return "{\"requestId\":" + longOf("select coalesce(max(id),999999) from order_transfer where order_id=? and kind='DIRECTED' and sub_kind='DIRECTED_RETURN'", orderId) + "}";
    }

    @Test
    @DisplayName("退回站长：申请→同意清空配送员；拒绝→按申请前状态恢复且配送员还在")
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

        // ---- 守卫：只能退自己名下的单；拒绝按申请前状态恢复，不再一律要求有配送员 ----
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
        assertEquals(0, post("/api/delivery/orders/return/" + order4 + "/reject", mgr, null).code(),
                "未接单订单拒绝退回不得被写成配送中，也不应再要求必须有配送员");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order4),
                "申请前是待配送，拒绝后仍应是待配送");
        assertNull(jdbc.queryForObject("SELECT delivery_staff_id FROM orders WHERE id=?", Object.class, order4));

        // ---- 情形 3：已分配但尚未接单（待配送 + 有配送员）→ 拒绝仍回待配送 ----
        long order5 = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order5);
        assertEquals(0, post("/api/delivery/orders/return/" + order5, del, "{\"reason\":\"尚未接单，先退回\"}").code());
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order5));
        assertEquals(0, post("/api/delivery/orders/return/" + order5 + "/reject", mgr, null).code());
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order5),
                "未接单（即使已分配）拒绝退回后不得变成配送中");
        assertEquals(delivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order5),
                "拒绝退回保留原指派，配送员可继续接单");
        assertNotEquals(0, post("/api/delivery/orders/return/" + order5 + "/reject", mgr, null).code(),
                "重复拒绝必须被已处理申请挡住");

        // ---- 历史申请缺来源状态：证据不足时拒绝猜测，订单与申请都不变 ----
        long order6 = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order6);
        assertEquals(0, post("/api/delivery/orders/return/" + order6, del, "{\"reason\":\"历史申请\"}").code());
        jdbc.update("UPDATE audit_log SET detail='' WHERE module='ORDER' AND action='RETURN_TO_STATION' AND target=?",
                "order:" + order6);
        assertNotEquals(0, post("/api/delivery/orders/return/" + order6 + "/reject", mgr, null).code(),
                "历史申请没有来源状态且无接单审计时，不能凭空猜成配送中");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order6));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='RETURN_STATION'", String.class, order6));

        // ---- 情形 4：申请后配送员又接单 → 旧申请不得处理新的配送中状态 ----
        long order7 = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order7);
        assertEquals(0, post("/api/delivery/orders/return/" + order7, del, "{\"reason\":\"先退回再说\"}").code());
        assertEquals(0, post("/api/delivery/orders/" + order7 + "/accept", del, null).code(),
                "退回待审期间配送员又接了单");
        assertNotEquals(0, post("/api/delivery/orders/return/" + order7 + "/reject", mgr, null).code(),
                "申请后新接单，旧申请不能直接审批");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order7),
                "拒绝失败不得改写当前配送中状态");
        assertEquals(delivery, longOf("SELECT delivery_staff_id FROM orders WHERE id=?", order7));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='RETURN_STATION'", String.class, order7));

        // ---- 情形 5：站长代退未分配单 → 之后分配并接单 → 旧申请不得处理新履约状态 ----
        // 申请时 fromStaffId 为空，之后分配并由配送员接单；旧申请已不能代表当前履约人，
        // 必须拒绝审批并保留原申请，不能拿旧 sourceStatus 改写现在的配送中状态。
        long order8 = createOrderFull(customer, address, station, product, 1, 2, 2,
                "10.00", "30.00", "40.00", true, 1);
        assertEquals(0, post("/api/delivery/orders/return/" + order8, mgr, "{\"reason\":\"站长代退\"}").code());
        assertEquals(0, post("/api/delivery/orders/assign/" + order8, mgr,
                "{\"deliveryStaffId\":" + delivery + "}").code(), "待审期间仍可分配");
        assertEquals(0, post("/api/delivery/orders/" + order8 + "/accept", del, null).code());
        assertNotEquals(0, post("/api/delivery/orders/return/" + order8 + "/reject", mgr, null).code(),
                "申请后分配并接单，旧申请不能直接处理新的配送中状态");
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order8),
                "拒绝失败不得改写当前配送中状态");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='RETURN_STATION'", String.class, order8),
                "旧申请必须保留待核实");

        // ---- 情形 6：A→B→A 后旧申请不得处理新履约 ----
        long order9 = createOrderFull(customer, address, station, product, 1, 1, 2,
                "10.00", "30.00", "40.00", true, 1);
        jdbc.update("UPDATE orders SET delivery_staff_id=? WHERE id=?", delivery, order9);
        assertEquals(0, post("/api/delivery/orders/return/" + order9, del, "{\"reason\":\"A 先退回\"}").code());
        assertEquals(0, post("/api/delivery/orders/assign/" + order9, mgr,
                "{\"deliveryStaffId\":" + otherDelivery + "}").code());
        assertEquals(0, post("/api/delivery/orders/assign/" + order9, mgr,
                "{\"deliveryStaffId\":" + delivery + "}").code());
        assertNotEquals(0, post("/api/delivery/orders/return/" + order9 + "/reject", mgr, null).code(),
                "A→B→A 后旧申请不能因为当前配送员又等于 A 就被当成有效");
        assertEquals(1, intOf("SELECT status FROM orders WHERE id=?", order9));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? AND sub_kind='RETURN_STATION'", String.class, order9));

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
        assertEquals(2, intOf("SELECT status FROM orders WHERE id=?", order1), "待审批申请不得抹掉已经接单的配送中事实");
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

        assertEquals(0, post("/api/delivery/orders/" + order1 + "/directed-return/approve", mgrOwner, decisionBody(order1)).code());
        assertEquals(ownerStation, longOf("SELECT delivery_station_id FROM orders WHERE id=?", order1),
                "同意后履约站改回归属站");
        assertNull(jdbc.queryForObject("SELECT delivery_staff_id FROM orders WHERE id=?", Object.class, order1));
        assertTrue(jdbc.queryForObject("SELECT special_note FROM orders WHERE id=?", String.class, order1)
                .contains("[指定退回-同意]"));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM order_transfer WHERE order_id=? "
                + "AND kind='DIRECTED'", String.class, order1));
        // 幂等/CAS：标记已被替换，再点一次必须失败，而不是把已经回到归属站的单再改一遍
        assertNotEquals(0, post("/api/delivery/orders/" + order1 + "/directed-return/approve", mgrOwner, decisionBody(order1)).code(),
                "重复同意必须被 CAS 守卫挡住");

        // ---- 情形 2：拒绝 → 回到配送中，履约站与配送员原样保留 ----
        long order2 = createOrderCrossStation(customer, address, ownerStation, fulfillStation, product,
                2, 2, 2, "10.00", "30.00", "40.00");
        assignDelivery(order2, fulfillDelivery, fulfillStation);
        assertEquals(0, post("/api/delivery/orders/" + order2 + "/directed-return", mgrFulfill, null).code());
        Api rejected = post("/api/delivery/orders/" + order2 + "/directed-return/reject", mgrOwner, decisionBody(order2));
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
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve", mgrFulfill, decisionBody(order3)).code(),
                "没有待确认标记时不得同意");
        assertEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return", mgrFulfill, null).code());
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve", mgrFulfill, decisionBody(order3)).code(),
                "只有原归属站可以同意退回");
        assertNotEquals(0, post("/api/delivery/orders/" + order3 + "/directed-return/approve",
                staffToken(fulfillDelivery, "DELIVERY", fulfillStation), decisionBody(order3)).code(),
                "配送员不得审批站间退回（且角色不符应被拒）");
    }
}
