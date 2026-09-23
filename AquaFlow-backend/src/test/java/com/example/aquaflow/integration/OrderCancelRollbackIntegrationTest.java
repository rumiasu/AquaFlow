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
 *   <li>只有「待配送(1) / 配送中(2)」可被取消；<b>已送达(3) 一律不可</b>（2026-09-21 裁定，见下条用例）。</li>
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

    /**
     * [2026-09-21 裁定改写] 已送达订单**不可再取消** —— 客户、配送员、站长三条路全被拒，且订单一动不动。
     *
     * <p><b>规则为什么改</b>：已送达不只是系统里的一个标记，现实里**货已经交付完成**。
     * 交付完成的事不该靠"取消"抹掉 —— 桶权益批次是在送达那一刻建的，而取消链的桶账处理
     * 够不着它（见 `PaymentServiceImpl.refundOrder` 的护栏注释），于是取消会留下
     * "客户握着可退押金的权益、而我们从未收到过押金"的敞口。客户拒付等异常改走「配送异常」流程。</p>
     *
     * <p><b>本用例改之前的样子</b>（留档，别照旧版做）：旧版断言"已送达单客户取消 → 转为站长审批的取消申请"，
     * 即 {@code put(/customer-cancel)} 返回成功、并落一条 {@code CANCEL_REQUEST}。那条路现已关闭。</p>
     */
    @Test
    @DisplayName("已送达订单不可取消（2026-09-21 裁定）：客户/配送员/站长三条路都被拒，订单与库存一动不动")
    void deliveredOrderIsNoLongerCancellable() {
        seed(true);
        // 已送达、待收款的现金订单（照真实形态：桶装水 + 待收款 1）
        long order = createOrderFull(customer, addr, station, product,
                3 /* 已送达 */, 1 /* 待收款 */, 2 /* 现金 */, "40.00", "0.00", "40.00", false, 2);
        long mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        String token = staffToken(mgr, "STATION_MANAGER", station);
        int qtyBefore = intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product);

        // ① 客户自助取消 → 拒，且**连取消申请都不该落库**（不是"转申请"，是根本不受理）
        Api byCustomer = put("/api/orders/" + order + "/customer-cancel", customerToken(customer), null);
        assertFalse(byCustomer.isSuccess(), "已送达单客户不得取消，实际=" + byCustomer);
        assertTrue(byCustomer.message() != null && byCustomer.message().contains("配送异常"),
                "拒绝文案必须指向异常流程（只回'不可取消'会让人反复重试），实际=" + byCustomer.message());

        // ② 拒单 → 拒
        Api byReject = post("/api/delivery/orders/reject/" + order, token, "{}");
        assertFalse(byReject.isSuccess(), "已送达单不得拒单，实际=" + byReject);

        // ③ 解决/拒单（这条会触发退款链）→ 拒
        Api byResolve = post("/api/delivery/orders/" + order + "/resolve", token,
                "{\"reason\":\"客户拒付\"}");
        assertFalse(byResolve.isSuccess(), "已送达单不得走解决/拒单，实际=" + byResolve);

        // 全程订单一动不动：状态、支付状态、库存、取消申请
        assertEquals(3, intOf("SELECT status FROM orders WHERE id=?", order), "三条路都不许改订单状态");
        assertEquals(1, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "支付状态同样不许被动过（仍是 待收款 1）");
        assertEquals(qtyBefore, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, product), "不得回补库存");
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_transfer WHERE order_id=? "
                        + "AND status='PENDING' AND sub_kind='CANCEL_REQUEST'", order),
                "不得留下任何待审批的取消申请（否则站长点同意时才发现拒不了）");
    }

    @Test
    @DisplayName("取消水票已付订单：水票退回、支付状态置已退款")
    void cancelTicketPaidOrder_refundsTicket() {
        seed(false);
        createTicketAccount(customer, station, product, 10);
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
