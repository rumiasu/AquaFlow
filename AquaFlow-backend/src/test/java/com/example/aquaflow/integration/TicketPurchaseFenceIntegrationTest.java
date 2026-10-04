package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP/MySQL 锁与回滚回归；须确认完整隔离目标，执行范围以准确版本 XML 与阶段交付为准。 */
class TicketPurchaseFenceIntegrationTest extends AbstractIntegrationTest {
    String purchase(long station, long product, String key) {
        return "{\"stationId\":" + station + ",\"productId\":" + product
                + ",\"quantity\":3,\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\"}";
    }
    Api close(String token, String key) {
        return post("/api/tickets/purchase-intent/close", token, "{\"idempotencyKey\":\"" + key + "\"}");
    }
    @Test void rejectedThenSealedIntentCannotCreateAfterCatalogFix() {
        long station = createStation("拒绝恢复站"), customer = createCustomer("拒绝恢复客户", "fence-reject");
        long product = createProduct("拒绝恢复水", 2, "20.00", "0.00", 0, "8.00");
        createInventoryFull(station, product, 20, 0, "8.00"); String token = customerToken(customer);
        assertEquals(1, post("/api/tickets/purchase", token, purchase(station, product, "old")).code());
        assertEquals(0, intOf("select count(*) from ticket_purchase_fence where customer_id=?", customer), "拒绝事务应回滚 fence 新行");
        Api closed = close(token, "old"); assertEquals(0, closed.code()); assertTrue(closed.data().path("closed").asBoolean());
        jdbc.update("update inventory set ticket_enabled=1 where station_id=? and product_id=?", station, product);
        jdbc.update("update product set ticket_enabled=1 where id=?", product);
        assertEquals(1, post("/api/tickets/purchase", token, purchase(station, product, "old")).code());
        assertEquals(0, post("/api/tickets/purchase", token, purchase(station, product, "new")).code());
        assertEquals(1, intOf("select count(*) from payment_record where customer_id=?", customer));
    }
    @Test void existingPendingMoneyAndNoTicketBalanceArePreserved() {
        long station = createStation("原款站"), customer = createCustomer("原款客户", "fence-pending");
        long product = createProduct("原款水", 2, "20.00", "0.00", 1, "8.00");
        createInventoryFull(station, product, 20, 1, "8.00"); String token = customerToken(customer);
        Api first = post("/api/tickets/purchase", token, purchase(station, product, "existing")); assertEquals(0, first.code());
        long id = first.data().path("paymentId").asLong(); Api closed = close(token, "existing");
        assertEquals(0, closed.code()); assertFalse(closed.data().path("closed").asBoolean());
        assertEquals(id, closed.data().path("payment").path("paymentId").asLong());
        assertEquals(1, intOf("select status from payment_record where id=?", id));
        assertEquals(0, decimalOf("select amount from payment_record where id=?", id).compareTo(new java.math.BigDecimal("24.00")));
        assertEquals(0, intOf("select count(*) from ticket_purchase_fence where customer_id=? and closed_time is not null", customer));
        assertEquals(0, intOf("select count(*) from ticket_lot where customer_id=?", customer));
    }
    @Test void sealAndCreateRaceHasOnlyOneAllowedOutcome() throws Exception {
        long station = createStation("并发站"), customer = createCustomer("并发客户", "fence-race");
        long product = createProduct("并发水", 2, "20.00", "0.00", 1, "8.00");
        createInventoryFull(station, product, 20, 1, "8.00"); String token = customerToken(customer);
        for (int i = 0; i < 4; i++) {
            String key = "race-" + i;
            ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            try {
                List<Callable<Api>> work = List.of(() -> post("/api/tickets/purchase", token, purchase(station, product, key)), () -> close(token, key));
                var futures = work.stream().map(task -> pool.submit(() -> { ready.countDown(); start.await(); return task.call(); })).toList();
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                Api created = futures.get(0).get(30, TimeUnit.SECONDS), closed = futures.get(1).get(30, TimeUnit.SECONDS);
                assertEquals(0, closed.code(), "终结须等锁并返回可判定结果");
                if (closed.data().path("closed").asBoolean()) {
                    assertEquals(1, created.code());
                    assertEquals(0, intOf("select count(*) from payment_record where customer_id=? and idempotency_key=?", customer, key));
                    assertEquals(1, post("/api/tickets/purchase", token, purchase(station, product, key)).code());
                } else {
                    assertEquals(0, created.code());
                    assertEquals(created.data().path("paymentId").asLong(), closed.data().path("payment").path("paymentId").asLong());
                    assertEquals(1, intOf("select count(*) from payment_record where customer_id=? and idempotency_key=?", customer, key));
                }
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void sealedTrimmedKeyAndEquivalentCollationArePermanentAndCustomerScoped() {
        long station = createStation("编号站"), customer = createCustomer("编号客户", "fence-key");
        long other = createCustomer("其他客户", "fence-other");
        long product = createProduct("编号水", 2, "20.00", "0.00", 1, "8.00"); createInventoryFull(station, product, 20, 1, "8.00");
        String token = customerToken(customer); assertTrue(close(token, " Key ").data().path("closed").asBoolean());
        assertTrue(close(token, "KEY").data().path("closed").asBoolean());
        assertEquals(1, intOf("select count(*) from ticket_purchase_fence where customer_id=?", customer));
        assertEquals(1, post("/api/tickets/purchase", token, purchase(station, product, "key")).code());
        assertEquals(0, post("/api/tickets/purchase", customerToken(other), purchase(station, product, "key")).code());
    }
}
