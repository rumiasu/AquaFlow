package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付流水回归（现金 / 水票）。
 *
 * <p>被锁死的口径：</p>
 * <ul>
 *   <li>现金货到付款「创建支付」只产出<b>待收款</b>流水，绝不能把订单变已付；
 *       金额一律以订单为准，客户端传 0.01 必须被丢弃（否则就是零元购）。</li>
 *   <li>收款确认只能成功一次：第二次必须被拒，且押金不得二次入账。</li>
 *   <li>水票支付在<b>支付</b>环节原子扣票（下单不扣），重复支付不重复扣。</li>
 * </ul>
 */
@DisplayName("Phase B · 支付（现金 / 水票）")
class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

    private long station;
    private long product;
    private long customer;
    private long addr;
    private long mgr;

    private void seed(boolean offlineEnabled) {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        addr = createAddress(customer, "某小区1号");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        if (offlineEnabled) {
            createCustomerStationConfig(customer, station, 1);
        }
    }

    private long createOrderViaApi(int paymentMethod, int qty) {
        String body = "{\"addressId\":" + addr + ",\"stationId\":" + station
                + ",\"paymentMethod\":" + paymentMethod + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\","
                + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}";
        Api res = post("/api/orders/create", customerToken(customer), body);
        assertTrue(res.isSuccess(), "下单应成功，实际=" + res);
        return res.data().path("orderId").asLong();
    }

    @Test
    @DisplayName("现金：创建支付只生成待收款，订单不变已付，金额以订单为准（忽略客户端 0.01）")
    void createCashPayment_staysPendingAndIgnoresClientAmount() {
        seed(true);
        long order = createOrderViaApi(2 /* 现金 */, 2);

        Api res = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":2,\"amount\":\"0.01\"}");
        assertTrue(res.isSuccess(), "创建现金支付应成功，实际=" + res);
        long paymentId = res.data().path("id").asLong();

        assertEquals(1, intOf("SELECT status FROM payment_record WHERE id=?", paymentId),
                "现金支付创建后必须是待收款(1)，不能直接已付(2)");
        assertEquals(0, intOf("SELECT payment_status FROM orders WHERE id=?", order),
                "订单支付状态不应在创建支付时就变已付");

        BigDecimal recordAmount = decimalOf("SELECT amount FROM payment_record WHERE id=?", paymentId);
        BigDecimal orderTotal = decimalOf("SELECT total_amount FROM orders WHERE id=?", order);
        assertEquals(0, recordAmount.compareTo(orderTotal),
                "支付金额必须以订单为准（客户端传 0.01 必须被丢弃），实际=" + recordAmount);
    }

    @Test
    @DisplayName("现金：确认收款入账一次并入账押金；重复确认被拒且不重复入账")
    void confirmCash_isOnceOnly() {
        seed(true);
        long order = createOrderViaApi(2 /* 现金 */, 2);

        Api pay = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":2}");
        long paymentId = pay.data().path("id").asLong();
        String token = staffToken(mgr, "STATION_MANAGER", station);

        Api first = put("/api/payments/" + paymentId + "/cash-confirm", token, null);
        assertTrue(first.isSuccess(), "确认收款应成功，实际=" + first);

        assertEquals(2, intOf("SELECT status FROM payment_record WHERE id=?", paymentId), "确认后支付记录应已付");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "订单应已付");

        BigDecimal balance = decimalOf("SELECT IFNULL(balance,0) FROM customer_deposit_account "
                + "WHERE customer_id=? AND station_id=?", customer, station);
        assertEquals(0, balance.compareTo(new BigDecimal("60.00")),
                "收款成功后应入账预收押金 60.00，实际=" + balance);

        Api second = put("/api/payments/" + paymentId + "/cash-confirm", token, null);
        assertFalse(second.isSuccess(), "重复确认应被拒，实际=" + second);
        assertEquals(1, intOf("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status=2", order),
                "只能存在一条已付流水");
        assertEquals(0, decimalOf("SELECT IFNULL(balance,0) FROM customer_deposit_account "
                        + "WHERE customer_id=? AND station_id=?", customer, station)
                        .compareTo(new BigDecimal("60.00")),
                "重复确认不得重复入账押金");
    }

    @Test
    @DisplayName("水票：下单支付原子扣票；重复支付不重复扣票")
    void ticketPayment_deductsAtomicallyAndIdempotently() {
        seed(false);
        createTicketAccount(customer, product, station, 10);
        long order = createOrderViaApi(3 /* 水票 */, 2);

        Api pay = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertTrue(pay.isSuccess(), "水票支付应成功，实际=" + pay);

        assertEquals(8, intOf("SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, station),
                "水票应从 10 原子扣减到 8");
        assertEquals(2, intOf("SELECT payment_status FROM orders WHERE id=?", order), "水票支付视同已付");
        assertEquals(1, intOf("SELECT COUNT(*) FROM ticket_record "
                        + "WHERE order_id=? AND product_id=? AND decrease_qty>0", order, product),
                "应产生一条消费流水");

        // 再对同一订单发起一次水票支付：必须幂等返回，且不得二次扣票
        Api retry = post("/api/payments", customerToken(customer),
                "{\"orderId\":" + order + ",\"paymentMethod\":3}");
        assertTrue(retry.isSuccess(), "重复支付应幂等返回，实际=" + retry);
        assertEquals(8, intOf("SELECT remain_quantity FROM ticket_account "
                        + "WHERE customer_id=? AND product_id=? AND station_id=?", customer, product, station),
                "重复支付不得重复扣票");
    }
}
