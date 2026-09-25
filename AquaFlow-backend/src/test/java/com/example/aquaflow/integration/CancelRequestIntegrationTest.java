package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「已接单订单的取消须站长审批」链路回归。
 *
 * <p>该链路（2026-09-14 引入）补的是两个此前的缺口：配送员接单后可零审批地直接取消
 * 已接单订单（还会触发退款）；而客户对已接单订单则完全取消不了。</p>
 *
 * <p>现在统一为「申请 → 站长决策」：申请只写 {@code order_transfer(..., PENDING)}，
 * <b>不动订单状态、不动任何账</b>；站长同意才走 {@code PaymentService.refundOrder}
 * 完整退款链，驳回则订单原样继续。</p>
 *
 * <p><b>本类守的核心不变量是「申请 ≠ 取消」</b>：一旦实现退化成"提交申请就把订单取消 / 就退款"，
 * 这里立刻变红。其余还守住收口（配送员不得直接拒单）、越权（他站站长不得审批）两条边界。</p>
 */
@DisplayName("Phase B · 取消申请（已接单订单的取消须站长审批）")
class CancelRequestIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;
    private long driver;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        driver = createStaff("D1", "DELIVERY", station, 1);
        // 水票余额 10 张：支付与退款都以它为准，便于一眼看出"票有没有真的退回来"
        // ⚠️ 参数顺序是 (客户, 水站, 商品)。历史上这里写反过，只因本站与商品的 id 恰好都是 1 才没暴露。
        createTicketAccount(customer, station, product, 10);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    private String driverToken() {
        return staffToken(driver, "DELIVERY", station);
    }

    /**
     * 走<b>真实链路</b>造一条「配送中(2) + 已付」的订单：下单 → 水票支付 → 配送员接单。
     *
     * <p>刻意不用 {@code createOrderFull} 手工插表：退款链要读支付流水、订单明细、库存扣减量等
     * 一串关联数据，手工造容易缺项，届时用例失败会分不清是"业务错"还是"造数不完整"。</p>
     */
    private long deliveringPaidOrder() {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":3,\"idempotencyKey\":\"" + UUID.randomUUID() + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":2}]}";
        Api created = post("/api/orders/create", customerToken(customer), body);
        assertTrue(created.isSuccess(), "前置：下单应成功，实际=" + created);
        long order = created.data().path("orderId").asLong();

        Api paid = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertTrue(paid.isSuccess(), "前置：水票支付应成功，实际=" + paid);

        Api accepted = post("/api/delivery/orders/" + order + "/accept", driverToken(), null);
        assertTrue(accepted.isSuccess(), "前置：配送员接单应成功，实际=" + accepted);

        assertEquals(2, orderStatus(order), "前置：订单应处于「配送中」");
        return order;
    }

    private int orderStatus(long orderId) {
        return intOf("SELECT status FROM orders WHERE id=?", orderId);
    }

    private int ticketRemain() {
        return intOf("SELECT remain_quantity FROM ticket_account WHERE customer_id=? AND station_id=? AND product_id=?",
                customer, station, product);
    }

    private int pendingCancelRequests(long orderId, String kind) {
        return intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND status='PENDING' "
                + "AND sub_kind='CANCEL_REQUEST' AND kind=?", orderId, kind);
    }

    /* ==================== 配送员发起 ==================== */

    @Test
    @DisplayName("配送员申请取消：申请落库为 PENDING，订单状态【不变】、水票不退")
    void staffRequest_doesNotChangeOrder() {
        seed();
        long order = deliveringPaidOrder();
        int tickBefore = ticketRemain();

        Api res = post("/api/delivery/orders/" + order + "/cancel-request", driverToken(),
                "{\"reason\":\"客户临时不要了\"}");

        assertTrue(res.isSuccess(), "配送员申请取消应成功，实际=" + res);
        assertEquals(2, orderStatus(order), "申请阶段订单必须保持「配送中」——申请不是取消");
        assertEquals(tickBefore, ticketRemain(), "申请阶段不得退水票（只有站长同意后才退）");
        assertEquals(1, pendingCancelRequests(order, "STAFF"), "应落一条 kind=STAFF 的待审批取消申请");
    }

    @Test
    @DisplayName("重复申请被拒：同一订单不堆积多条待审批取消申请")
    void duplicateRequest_isRejected() {
        seed();
        long order = deliveringPaidOrder();

        Api first = post("/api/delivery/orders/" + order + "/cancel-request", driverToken(), "{}");
        Api second = post("/api/delivery/orders/" + order + "/cancel-request", driverToken(), "{}");

        assertTrue(first.isSuccess(), "首次申请应成功，实际=" + first);
        assertFalse(second.isSuccess(), "重复申请应被拒，实际=" + second);
        assertEquals(1, pendingCancelRequests(order, "STAFF"), "库里仍只能有一条待审批申请");
    }

    /* ==================== 收口：配送员不得直接取消已接单订单 ==================== */

    @Test
    @DisplayName("收口：配送员不能对「已接单」订单直接拒单（必须走申请）")
    void staffCannotDirectlyRejectAcceptedOrder() {
        seed();
        long order = deliveringPaidOrder();
        int tickBefore = ticketRemain();

        Api res = post("/api/delivery/orders/reject/" + order, driverToken(), "{\"reason\":\"送不了\"}");

        assertFalse(res.isSuccess(), "已接单订单不得被配送员直接拒单，实际=" + res);
        assertEquals(2, orderStatus(order), "被拒后订单状态必须保持原状");
        assertEquals(tickBefore, ticketRemain(), "被拒后不得退水票");
    }

    /* ==================== 站长决策 ==================== */

    @Test
    @DisplayName("站长同意：订单置已取消 + 水票退回，申请置 APPROVED")
    void managerApprove_cancelsAndRefunds() {
        seed();
        long order = deliveringPaidOrder();
        assertTrue(post("/api/delivery/orders/" + order + "/cancel-request", driverToken(), "{}").isSuccess());

        Api res = post("/api/delivery/orders/cancel-request/" + order + "/approve", mgrToken(), null);

        assertTrue(res.isSuccess(), "站长同意应成功，实际=" + res);
        assertEquals(5, orderStatus(order), "同意后订单应置「已取消(5)」");
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "已付订单取消后支付状态应为「已退款(3)」");
        assertEquals(10, ticketRemain(), "水票必须回到 10（退款链真的把票退了）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND status='APPROVED'",
                order), "申请应置 APPROVED");
        assertEquals(0, pendingCancelRequests(order, "STAFF"), "申请不应再处于 PENDING");
    }

    @Test
    @DisplayName("站长驳回：订单保持配送中、申请置 REJECTED，不动任何账")
    void managerReject_orderContinues() {
        seed();
        long order = deliveringPaidOrder();
        int tickBefore = ticketRemain();
        assertTrue(post("/api/delivery/orders/" + order + "/cancel-request", driverToken(), "{}").isSuccess());

        Api res = post("/api/delivery/orders/cancel-request/" + order + "/reject", mgrToken(), null);

        assertTrue(res.isSuccess(), "站长驳回应成功，实际=" + res);
        assertEquals(2, orderStatus(order), "驳回后订单必须继续「配送中」");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "驳回不得改动支付状态（仍为已付 2）");
        assertEquals(tickBefore, ticketRemain(), "驳回不得退水票");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND status='REJECTED'",
                order), "申请应置 REJECTED");
    }

    /* ==================== 客户发起 ==================== */

    @Test
    @DisplayName("客户对「配送中」订单取消：转为客户取消申请，订单与库存均不变")
    void customerCancelOnDelivering_becomesRequest() {
        seed();
        long order = deliveringPaidOrder();
        int qtyBefore = intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product);

        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);

        assertTrue(res.isSuccess(), "已接单订单客户提交取消申请应成功，实际=" + res);
        assertEquals(2, orderStatus(order), "申请阶段订单必须保持「配送中」");
        assertEquals(qtyBefore, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "申请阶段不得回补库存");
        assertEquals(1, pendingCancelRequests(order, "CUSTOMER"), "应落一条 kind=CUSTOMER 的待审批申请");
    }

    /* ==================== 越权 ==================== */

    @Test
    @DisplayName("越权：他站站长不能审批本单的取消申请")
    void managerCannotApproveOtherStationsRequest() {
        seed();
        long order = deliveringPaidOrder();
        assertTrue(post("/api/delivery/orders/" + order + "/cancel-request", driverToken(), "{}").isSuccess());

        long otherStation = createStation("S2");
        long otherMgr = createStaff("M2", "STATION_MANAGER", otherStation, 1);

        Api res = post("/api/delivery/orders/cancel-request/" + order + "/approve",
                staffToken(otherMgr, "STATION_MANAGER", otherStation), null);

        assertFalse(res.isSuccess(), "他站站长不应能审批本单，实际=" + res);
        assertEquals(2, orderStatus(order), "越权被拒后订单必须保持原状");
        assertEquals(1, pendingCancelRequests(order, "STAFF"), "申请应仍处于待审批");
    }
}
