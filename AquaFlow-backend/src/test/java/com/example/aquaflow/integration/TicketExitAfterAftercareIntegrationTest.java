package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 购票 → 部分用票 → 退剩余票 → 订单售后回补已用票 → 再退回补票。
 *
 * <p>旧实现把 {@code ticket_exit_refund.original_payment_id} 做成主键，并在一开始
 * {@code not exists} 排除整笔原款，导致售后回补的票有真实来源却永远不能再退出。
 * 这里同时守累计数量/金额上限、最后一张尾差、同键重试和并发。</p>
 */
@TestPropertySource(properties={"app.payment.test-mode=true"})
class TicketExitAfterAftercareIntegrationTest extends AbstractIntegrationTest {
    long station, manager, customer, product, address;
    String cus, mgr;

    void seed() {
        station = createStation("退票售后站");
        manager = createStaff("退票站长", "STATION_MANAGER", station, 1);
        customer = createCustomer("退票售后客户", "ticket-exit-aftercare");
        product = createProduct("退票水", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 100, 1, "18.00");
        address = createAddress(customer, "退票地址");
        createTicketAccount(customer, station, product, 0);
        createBarrelAsset(customer, station, product, 10, "300.00");
        createBarrelLot("TICKET-EXIT-RIGHT", customer, station, product, "30", 10, 10);
        createCustomerStationConfig(customer, station, 1);
        assertTrue(saveDeliveryConfig(staffToken(manager, "STATION_MANAGER", station),
                "{\"baseDeliveryFee\":3,\"floorFeePerLevel\":2,\"floorFreeLevel\":1}").isSuccess());
        jdbc.update("update address set floor=3,has_elevator=0 where id=?", address);
        cus = customerToken(customer);
        mgr = staffToken(manager, "STATION_MANAGER", station);
    }

    String purchaseBody(int qty, String key) {
        return "{\"stationId\":" + station + ",\"productId\":" + product + ",\"quantity\":" + qty
                + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\"}";
    }

    long buyTickets(int qty, String key) {
        Api bought = post("/api/tickets/purchase", cus, purchaseBody(qty, key));
        assertEquals(0, bought.code(), bought.toString());
        long payment = bought.data().path("paymentId").asLong();
        assertEquals(0, put("/api/payments/" + payment + "/confirm", mgr, null).code());
        return payment;
    }

    String orderBody(String key, int qty) {
        return "{\"stationId\":" + station + ",\"addressId\":" + address + ",\"paymentMethod\":3,"
                + "\"ticketFeePaymentMethod\":2,\"ticketFeeQuoteAmount\":7,\"ticketDeliveryCreditQuote\":0,"
                + "\"idempotencyKey\":\"" + key + "\",\"items\":[{\"productId\":" + product
                + ",\"quantity\":" + qty + "}]}";
    }

    long createTicketOrder(String key, int qty) {
        Api made = post("/api/orders/create", cus, orderBody(key, qty));
        assertEquals(0, made.code(), made.toString());
        long order = made.data().path("orderId").asLong();
        assertTrue(order > 0, made.toString());
        return order;
    }

    Api payTicketOrder(long order) {
        return post("/api/payments", cus, "{\"orderId\":" + order + ",\"paymentMethod\":3,\"amount\":0.01}");
    }

    void deliver(long order, int returnBucketQty) {
        Api accepted = post("/api/delivery/orders/" + order + "/accept", mgr, null);
        assertTrue(accepted.isSuccess(), accepted.toString());
        Api done = post("/api/delivery/orders/" + order + "/complete", mgr,
                "{\"returnBucketQty\":" + returnBucketQty + ",\"collected\":true}");
        assertTrue(done.isSuccess(), done.toString());
    }

    long orderPayment(long order) {
        return jdbc.queryForObject("select id from payment_record where order_id=? and status=2 order by id limit 1", Long.class, order);
    }

    Api exitTickets(long payment, int qty, String amount, String key) {
        return put("/api/payments/" + payment + "/refund", mgr,
                "{\"scope\":\"WATER\",\"expectedTicketQty\":" + qty + ",\"expectedTicketAmount\":" + amount
                        + ",\"idempotencyKey\":\"" + key + "\"}");
    }

    @Test
    void partialExitAfterAftercareReturnCanExitAgainAndStopsAtOriginalAmount() {
        seed();
        long payment = buyTickets(3, "buy-three");
        long order = createTicketOrder("ticket-order-one", 1);
        assertTrue(payTicketOrder(order).isSuccess());
        deliver(order, 1);

        // 购 3、用 1，先退剩余 2；原款仍是 PAID，等待售后回补。
        Api first = exitTickets(payment, 2, "36.00", "exit-remaining-two");
        assertEquals(0, first.code(), first.toString());
        assertEquals(1, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(2, intOf("select quantity from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(2, intOf("select status from payment_record where id=?", payment));

        // 订单售后只回补水票、不退现金：1 张回到原购票批次。
        Api water = put("/api/payments/" + orderPayment(order) + "/refund", mgr,
                "{\"scope\":\"WATER\",\"expectedTicketQty\":1,\"expectedRefundAmount\":0,\"note\":\"售后回补原批次\"}");
        assertEquals(0, water.code(), water.toString());
        assertEquals(1, intOf("select coalesce(sum(remain_qty),0) from ticket_lot where payment_record_id=?", payment));
        assertEquals(1, intOf("select remain_quantity from ticket_account where customer_id=? and station_id=? and product_id=?", customer, station, product));

        Api batches = get("/api/manager/ticket-exit-batches", mgr);
        assertEquals(0, batches.code(), batches.toString());
        assertEquals(1, batches.data().get(0).path("remainingQty").asInt());
        assertEquals(0, new BigDecimal("18.00").compareTo(batches.data().get(0).path("refundAmount").decimalValue()));

        // 第二次退出：累计数量达到原购票 3 张，原款置 REFUNDED。
        Api second = exitTickets(payment, 1, "18.00", "exit-returned-one");
        assertEquals(0, second.code(), second.toString());
        assertEquals(2, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(3, intOf("select coalesce(sum(quantity),0) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(0, new BigDecimal("54.00").compareTo(decimalOf("select coalesce(sum(amount),0) from ticket_exit_refund where original_payment_id=?", payment)));
        assertEquals(3, intOf("select status from payment_record where id=?", payment));
        assertEquals(0, intOf("select coalesce(sum(remain_qty),0) from ticket_lot where payment_record_id=?", payment));
        assertEquals(0, intOf("select remain_quantity from ticket_account where customer_id=? and station_id=? and product_id=?", customer, station, product));

        // 同键重试是幂等返回，不再多一条凭据/退款流水；换键则被余额与累计上限拒绝。
        Api replay = exitTickets(payment, 1, "18.00", "exit-returned-one");
        assertEquals(0, replay.code(), replay.toString());
        assertEquals(2, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(2, intOf("select count(*) from payment_record where customer_id=? and amount<0", customer));
        assertEquals(1, exitTickets(payment, 1, "17.99", "exit-returned-one").code(),
                "同一请求键换了金额必须拒绝，不能把重试当成新的退款");
        assertEquals(1, exitTickets(payment, 1, "18.00", "exit-third").code());
        assertEquals(2, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(0, new BigDecimal("0.00").compareTo(decimalOf("select right_amount from ticket_account where customer_id=? and station_id=? and product_id=?", customer, station, product)));
    }

    @Test
    void missingRequestKeyRejectedBeforeConsumingTickets() {
        seed();
        long payment = buyTickets(3, "buy-no-key");
        Api noKey = put("/api/payments/" + payment + "/refund", mgr,
                "{\"scope\":\"WATER\",\"expectedTicketQty\":3,\"expectedTicketAmount\":54.00}");
        assertEquals(1, noKey.code(), noKey.toString());
        assertEquals(3, intOf("select coalesce(sum(remain_qty),0) from ticket_lot where payment_record_id=?", payment));
        assertEquals(0, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(2, intOf("select status from payment_record where id=?", payment));
    }

    @Test
    void finalPartialExitUsesRemainingOriginalAmountInsteadOfRoundedUnitPriceTail() {
        seed();
        long payment = insert("insert into payment_record(order_id,customer_id,station_id,payment_method,amount,water_amount,barrel_deposit,status,ticket_qty,ticket_water_type_id) "
                + "values(null,?,?,2,23.99,23.99,0,2,3,?)", customer, station, product);
        insert("insert into ticket_lot(lot_no,customer_id,station_id,product_id,unit_price,qty,remain_qty,source_type,price_source,is_migrated,payment_record_id,status) "
                + "values('TAIL-LOT',?,?,?,8.00,3,3,1,1,0,?,1)", customer, station, product, payment);
        jdbc.update("update ticket_account set remain_quantity=3,right_amount=24.00 where customer_id=? and station_id=? and product_id=?", customer, station, product);

        long order = createTicketOrder("ticket-order-tail", 1);
        assertTrue(payTicketOrder(order).isSuccess());
        deliver(order, 1);
        Api first = exitTickets(payment, 2, "16.00", "tail-first");
        assertEquals(0, first.code(), first.toString());
        Api water = put("/api/payments/" + orderPayment(order) + "/refund", mgr,
                "{\"scope\":\"WATER\",\"expectedTicketQty\":1,\"expectedRefundAmount\":0,\"note\":\"尾差售后回补\"}");
        assertEquals(0, water.code(), water.toString());

        Api last = exitTickets(payment, 1, "7.99", "tail-last");
        assertEquals(0, last.code(), last.toString());
        assertEquals(0, new BigDecimal("23.99").compareTo(decimalOf("select coalesce(sum(amount),0) from ticket_exit_refund where original_payment_id=?", payment)));
        assertEquals(3, intOf("select status from payment_record where id=?", payment));
        assertEquals(0, intOf("select coalesce(sum(remain_qty),0) from ticket_lot where payment_record_id=?", payment));
        assertEquals(0, new BigDecimal("0.00").compareTo(decimalOf("select right_amount from ticket_account where customer_id=? and station_id=? and product_id=?", customer, station, product)));
    }

    @Test
    void concurrentSameRequestKeyCreatesOneExitReceipt() throws Exception {
        seed();
        long payment = buyTickets(3, "buy-race");
        long order = createTicketOrder("ticket-order-race", 1);
        assertTrue(payTicketOrder(order).isSuccess());
        deliver(order, 1);
        assertEquals(0, exitTickets(payment, 2, "36.00", "race-first").code());
        Api water = put("/api/payments/" + orderPayment(order) + "/refund", mgr,
                "{\"scope\":\"WATER\",\"expectedTicketQty\":1,\"expectedRefundAmount\":0,\"note\":\"并发售后回补\"}");
        assertEquals(0, water.code(), water.toString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Api>> tasks = List.of(
                    () -> { start.await(); return exitTickets(payment, 1, "18.00", "race-same-key"); },
                    () -> { start.await(); return exitTickets(payment, 1, "18.00", "race-same-key"); });
            List<Future<Api>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Api> future : futures) assertEquals(0, future.get(30, TimeUnit.SECONDS).code());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, intOf("select count(*) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(3, intOf("select coalesce(sum(quantity),0) from ticket_exit_refund where original_payment_id=?", payment));
        assertEquals(2, intOf("select count(*) from payment_record where customer_id=? and amount<0", customer));
        assertEquals(3, intOf("select status from payment_record where id=?", payment));
        assertEquals(0, intOf("select coalesce(sum(remain_qty),0) from ticket_lot where payment_record_id=?", payment));
    }
}
