package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消订单回滚回归。
 *
 * <p>下单会产生一串副作用：扣库存、建「配送中」桶权益、扣水票、写支付状态。
 * 取消时必须<b>逐项、按实际发生量</b>回滚——少回滚等于账实不符，多回滚等于可以靠「下单-取消」刷库存。</p>
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>只有「待配送」可被客户取消；已送达订单必须原样保持。</li>
 *   <li>从未付过钱的订单取消后是「已取消」，不是「已退款」——假的退款记录比没有记录更糟。</li>
 *   <li>水票支付的订单取消要把票退回去。</li>
 * </ul>
 */
@DisplayName("Phase B · 取消订单回滚")
class OrderCancelRollbackIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;

    private void seed(boolean offlineEnabled) {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        if (offlineEnabled) {
            createCustomerStationConfig(customer, station, 1);
        }
    }

    /** 走真实下单接口建单，返回订单 id。 */
    private long createOrderViaApi(int paymentMethod, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":" + paymentMethod + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}";
        Api res = post("/api/orders/create", customerToken(customer), body);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        return res.data().path("orderId").asLong();
    }

    @Test
    @DisplayName("取消未付款订单：库存回补、配送中桶清理、支付状态置已取消")
    void cancelUnpaidOrder_rollsBackConsistently() {
        seed(true);
        long order = createOrderViaApi(2 /* 现金 */, 2);

        assertEquals(8, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product));
        assertEquals(1, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit WHERE related_order_id=?", order));

        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(res.isSuccess(), "客户取消待配送订单应成功，实际=" + res);

        // 从未付款的订单不该留下任何押金动作：既没入过账，也就不存在"释放"。
        // （orders.deposit_amount > 0 只代表"应收押金"，不代表钱到过账上。）
        assertEquals(0, intOf("SELECT COUNT(*) FROM deposit_record WHERE related_order_id=?", order),
                "未付款订单取消不得产生任何押金流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_deposit_account"), "不得凭空创建押金账户");

        assertEquals(5, intOf("SELECT status FROM orders WHERE id=?", order), "订单应置已取消");
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        station, product),
                "库存必须回补到 10");
        assertEquals(0, intOf("SELECT COUNT(*) FROM customer_barrel_in_transit WHERE related_order_id=?", order),
                "配送中桶必须清理");
        assertEquals(4, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "从未付款的订单取消后应为「已取消(4)」，而非「已退款」");
    }

    @Test
    @DisplayName("已送达订单客户取消 → 转为站长审批的取消申请，订单与库存均不变")
    void deliveredOrder_cancelBecomesApprovalRequest() {
        seed(true);
        // 已送达、未付款的现金订单
        long order = createOrderFull(customer, addr, station, product,
                3 /* 已送达 */, 0, 2 /* 现金 */, "40.00", "0.00", "40.00", false, 2);
        int qtyBefore = intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product);

        // [2026-09-14] 语义变更：已接单（配送中/已送达）的订单，客户不再被直接拒绝，
        // 而是提交取消申请（order_transfer kind=CUSTOMER / subKind=CANCEL_REQUEST），
        // 由站长审批；站长同意后才走 refundOrder 完整退款链。
        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(res.isSuccess(), "已送达订单应转为提交取消申请，实际=" + res);

        // 申请 ≠ 取消：订单状态与库存必须原封不动，否则就成了「申请即退款」的事故。
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order),
                "提交申请后状态必须保持已送达");
        assertEquals(qtyBefore, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                        station, product),
                "提交申请不得回补库存（只有站长同意后才回补）");
        assertEquals(1, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? AND status='PENDING' "
                        + "AND kind='CUSTOMER' AND sub_kind='CANCEL_REQUEST'", order),
                "应恰好生成一条客户取消申请");
    }

    @Test
    @DisplayName("取消水票已付订单：水票退回、支付状态置已退款")
    void cancelTicketPaidOrder_refundsTicket() {
        seed(false);
        createTicketAccount(customer, product, station, 10);
        long order = createOrderViaApi(3 /* 水票 */, 2);

        Api pay = post("/api/payments", customerToken(customer), "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertTrue(pay.isSuccess(), "水票支付应成功，实际=" + pay);

        assertEquals(8, intOf("SELECT remain_quantity FROM ticket_account "
                + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, station),
                "支付后水票应为 8");

        Api res = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertTrue(res.isSuccess(), "取消已付水票订单应成功，实际=" + res);

        assertEquals(10, intOf("SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, station),
                "取消后水票必须退回 10");
        assertEquals(3, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "已付订单取消后支付状态应为「已退款(3)」");
    }
}
