package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 收款核销和取消必须保留原渠道事实，不能把付款意图当成收到的钱。 */
class ReceivablePaymentTruthIntegrationTest extends AbstractIntegrationTest {
    private long station, manager, customer, address, product;
    private String managerToken, customerToken;

    private void seed() {
        station = createStation("核销原款站");
        manager = createStaff("核销原款站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("核销原款客户", "receivable-payment-truth");
        address = createAddress(customer, "核销原款地址");
        product = createProduct("核销原款水", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 100, 1, "18.00");
        createCustomerStationConfig(customer, station, 1);
        managerToken = staffToken(manager, "STATION_MANAGER", station);
        customerToken = customerToken(customer);
    }

    private long order(int method, String key) {
        Api created = post("/api/orders/create", customerToken,
                "{\"stationId\":" + station + ",\"addressId\":" + address
                        + ",\"paymentMethod\":" + method + ",\"idempotencyKey\":\"" + key
                        + "\",\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
        assertTrue(created.isSuccess(), created.toString());
        return created.data().path("orderId").asLong();
    }

    private long payment(long order, int method) {
        Api payment = post("/api/payments", customerToken,
                "{\"orderId\":" + order + ",\"paymentMethod\":" + method + "}");
        assertTrue(payment.isSuccess(), payment.toString());
        return payment.data().path("id").asLong();
    }

    private Api settle(long... orders) {
        String ids = java.util.Arrays.stream(orders).mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return post("/api/manager/receivables/settle", managerToken,
                "{\"customerId\":" + customer + ",\"orderIds\":[" + ids + "]}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unpaidWechatCannotBecomePaidThroughReceivableSettlement(boolean pendingRecordExists) {
        seed();
        long order = order(1, "receivable-unpaid-wechat");
        if (pendingRecordExists) payment(order, 1);
        int originalPaymentStatus = intOf("select payment_status from orders where id=?", order);

        Api settled = settle(order);

        assertEquals(1, settled.code(), settled.toString());
        assertEquals(originalPaymentStatus, intOf("select payment_status from orders where id=?", order));
        assertEquals(1, intOf("select settlement_status from orders where id=?", order));
        assertEquals(0, intOf("select count(*) from payment_record where order_id=? and status=2", order));
        assertEquals(pendingRecordExists ? 1 : 0,
                intOf("select count(*) from payment_record where order_id=? and status=1 and payment_method=1", order));
        assertEquals(0, intOf("select count(*) from deposit_record where related_order_id=?", order));
    }

    @Test
    void invalidOnlineOrderRollsBackEarlierCashCollectionInTheSameBatch() {
        seed();
        long cash = order(2, "receivable-batch-cash");
        long cashPayment = payment(cash, 2);
        long online = order(1, "receivable-batch-online");
        long onlinePayment = payment(online, 1);

        assertEquals(1, settle(cash, online).code());

        assertEquals(1, intOf("select status from payment_record where id=?", cashPayment));
        assertEquals(1, intOf("select status from payment_record where id=?", onlinePayment));
        assertEquals(0, intOf("select count(*) from orders where id in (?,?) and settlement_status=2", cash, online));
        assertEquals(0, intOf("select count(*) from payment_record where status=2"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cashCollectionRejectsAnOriginalRecordWithDifferentMethodOrAmount(boolean wrongAmount) {
        seed();
        long order = order(2, "receivable-mismatch");
        long payment = payment(order, 2);
        if (wrongAmount) jdbc.update("update payment_record set amount=amount+1 where id=?", payment);
        else jdbc.update("update payment_record set payment_method=1 where id=?", payment);

        assertEquals(1, settle(order).code());

        assertEquals(1, intOf("select status from payment_record where id=?", payment));
        assertEquals(1, intOf("select payment_status from orders where id=?", order));
        assertEquals(1, intOf("select settlement_status from orders where id=?", order));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualCashCollectionStillWritesOneVoucherAndReplaysWithoutAnotherCollection(boolean pendingRecordExists) {
        seed();
        long order = order(2, "receivable-valid-cash");
        if (pendingRecordExists) payment(order, 2);

        Api collected = settle(order);
        assertTrue(collected.isSuccess(), collected.toString());
        assertEquals(1, collected.data().path("collectedCount").asInt());
        assertEquals(2, intOf("select payment_status from orders where id=?", order));
        assertEquals(1, intOf("select count(*) from payment_record where order_id=? and status=2 and payment_method=2", order));
        assertEquals(0, decimalOf("select amount from payment_record where order_id=? and status=2", order)
                .compareTo(decimalOf("select total_amount from orders where id=?", order)));
        Api replay = settle(order);
        assertTrue(replay.isSuccess(), replay.toString());
        assertEquals(0, replay.data().path("collectedCount").asInt());
        assertEquals(1, intOf("select count(*) from payment_record where order_id=?", order));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingUncollectedCashClosesOriginalVoucherWithoutInventingRefund(boolean ticketFees) {
        seed();
        long order;
        if (ticketFees) {
            createTicketAccount(customer, station, product, 10);
            createBarrelAsset(customer, station, product, 10, "300.00");
            createBarrelLot("CANCEL-COD-RIGHTS", customer, station, product, "30", 10, 10);
            assertTrue(put("/api/manager/delivery-config", managerToken,
                    "{\"baseDeliveryFee\":3,\"floorFeePerLevel\":0,\"floorFreeLevel\":1}").isSuccess());
            Api created = post("/api/orders/create", customerToken,
                    "{\"stationId\":" + station + ",\"addressId\":" + address
                            + ",\"paymentMethod\":3,\"ticketFeePaymentMethod\":2,\"ticketFeeQuoteAmount\":3,"
                            + "\"ticketDeliveryCreditQuote\":0,\"idempotencyKey\":\"cancel-cod-ticket-fees\","
                            + "\"items\":[{\"productId\":" + product + ",\"quantity\":1}]}");
            assertTrue(created.isSuccess(), created.toString());
            order = created.data().path("orderId").asLong();
        } else {
            order = order(2, "cancel-cod-original");
            payment(order, 2);
        }
        long original = longOf("select id from payment_record where order_id=? and status=1", order);
        var originalAmount = decimalOf("select amount from payment_record where id=?", original);

        Api cancelled = put("/api/orders/" + order + "/customer-cancel", customerToken, null);

        assertTrue(cancelled.isSuccess(), cancelled.toString());
        assertEquals(5, intOf("select status from orders where id=?", order));
        assertEquals(4, intOf("select payment_status from orders where id=?", order));
        assertEquals(4, intOf("select status from payment_record where id=?", original));
        assertEquals(0, originalAmount.compareTo(decimalOf("select amount from payment_record where id=?", original)));
        assertEquals(1, intOf("select count(*) from payment_record where order_id=?", order));
        assertEquals(0, intOf("select count(*) from payment_record where order_id=? and (status in (1,2,3) or amount<0)", order));
        assertEquals(1, put("/api/payments/" + original + "/cash-confirm", managerToken, null).code());
        if (ticketFees) {
            assertEquals("CANCELLED", jdbc.queryForObject("select state from order_ticket_payment where order_id=?", String.class, order));
            assertEquals(10, intOf("select remain_quantity from ticket_account where customer_id=? and station_id=? and product_id=?", customer, station, product));
        }
    }

    @Test
    void cancellingUnresolvedWechatRequestDoesNotInventChannelCancellationOrRefund() {
        seed();
        long order = order(1, "cancel-unresolved-wechat");
        long original = payment(order, 1);

        assertTrue(put("/api/orders/" + order + "/customer-cancel", customerToken, null).isSuccess());

        assertEquals(1, intOf("select status from payment_record where id=?", original));
        assertEquals(1, intOf("select payment_method from payment_record where id=?", original));
        assertEquals(1, intOf("select count(*) from payment_record where order_id=?", order));
        assertEquals(0, intOf("select count(*) from payment_record where order_id=? and (status in (2,3) or amount<0)", order));
    }
}
