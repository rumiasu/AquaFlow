package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real role/HTTP/SQL receipts, unique-key races and reversal locks in an explicitly confirmed synthetic database. */
@TestPropertySource(properties = "alert.system-webhook=")
class StationAdjustmentSafetyIntegrationTest extends AbstractIntegrationTest {
    private long station;
    private long customer;
    private String manager;

    private void seed() {
        station = createStation("调整安全合成站");
        customer = createCustomer("调整安全合成客户", "adjustment-synthetic-customer");
        createCustomerStationConfig(customer, station, 1);
        manager = staffToken(createStaff("调整安全合成站长", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    private String body(long targetCustomer, String amount, String key) {
        return om.valueToTree(Map.of("customerId", targetCustomer, "adjustType", "DEPOSIT_GRANT",
                "amount", new BigDecimal(amount), "reason", "合成调整", "evidence", "合成核对凭据", "clientToken", key)).toString();
    }

    private Api create(String amount, String key) {
        return post("/api/manager/adjustments", manager, body(customer, amount, key));
    }

    private long effective(String amount, String key) {
        Api made = create(amount, key); assertEquals(0, made.code(), made.toString());
        long id = made.data().path("id").asLong();
        assertEquals(0, post("/api/manager/adjustments/" + id + "/execute", manager, "{}").code());
        return id;
    }

    private Api reverse(long id, String key) {
        return post("/api/manager/adjustments/" + id + "/reverse", manager,
                om.valueToTree(Map.of("reason", "合成纠错", "clientToken", key)).toString());
    }

    private BigDecimal balance() {
        return decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?", customer, station);
    }

    @Test void crossStationSameKeyDoesNotExposeAdjustmentOrCustomerProfile() {
        seed(); Api own = create("10.00", "cross-station-key"); assertEquals(0, own.code());
        long otherStation = createStation("另一合成站");
        long otherCustomer = createCustomer("另一合成客户", "adjustment-other-customer");
        createCustomerStationConfig(otherCustomer, otherStation, 1);
        String otherManager = staffToken(createStaff("另一合成站长", "STATION_MANAGER", otherStation, 1), "STATION_MANAGER", otherStation);
        Api denied = post("/api/manager/adjustments", otherManager, body(otherCustomer, "10.00", "cross-station-key"));
        assertEquals(1, denied.code()); assertTrue(denied.data().isNull() || denied.data().isMissingNode());
        assertEquals(1, intOf("select count(*) from station_adjustment"));
        assertEquals(1, get("/api/manager/adjustments/" + own.data().path("id").asLong(), otherManager).code());
        assertEquals(1, post("/api/manager/adjustments/" + own.data().path("id").asLong() + "/execute", otherManager, "{}").code());
    }

    @Test void sameKeyChangedContentsAreRejectedAndTheOriginalReceiptIsStable() throws Exception {
        seed(); Api first = create("10.00", "same-content-key"); assertEquals(0, first.code());
        Api retry = create("10.0", "same-content-key"); assertEquals(0, retry.code());
        assertEquals(first.data().path("id"), retry.data().path("id"));
        long otherCustomer = createCustomer("本站另一合成客户", "adjustment-second-customer");
        createCustomerStationConfig(otherCustomer, station, 1);
        for (String changed : List.of("customerId", "adjustType", "productId", "qty", "amount", "unitPrice", "reason", "evidence")) {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(body(customer, "10.00", "same-content-key"));
            switch (changed) {
                case "customerId" -> payload.put(changed, otherCustomer);
                case "adjustType" -> payload.put(changed, "DEPOSIT_DEDUCT");
                case "productId" -> payload.put(changed, 999999L);
                case "qty" -> payload.put(changed, 1);
                case "amount" -> payload.put(changed, 11);
                case "unitPrice" -> payload.put(changed, 1);
                default -> payload.put(changed, "不同合成内容");
            }
            assertEquals(1, post("/api/manager/adjustments", manager, payload.toString()).code(), changed);
        }
        assertEquals(1, intOf("select count(*) from station_adjustment"));
        assertEquals(0, intOf("select count(*) from deposit_record"));
    }

    @Test void monetaryInputsCannotBeSilentlyRoundedOrOverflowTheReceipt() throws Exception {
        seed();
        for (String field : List.of("amount", "unitPrice")) {
            for (String number : List.of("10.001", "100000000.00")) {
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(body(customer, "10.00", "unrepresentable-money"));
                payload.put(field, new BigDecimal(number));
                assertEquals(1, post("/api/manager/adjustments", manager, payload.toString()).code(), field + "=" + number);
            }
        }
        assertEquals(0, intOf("select count(*) from station_adjustment"));
        assertEquals(0, intOf("select count(*) from deposit_record"));
        Api first = create("10.0000", "trailing-zero-money"); assertEquals(0, first.code());
        Api retry = create("10.00", "trailing-zero-money"); assertEquals(0, retry.code());
        assertEquals(first.data().path("id"), retry.data().path("id"));
    }

    @Test void pendingCreateKeyCannotRedirectReversalAndCorrectRetryChangesMoneyOnlyOnce() {
        seed(); long original = effective("10.00", "original-key");
        Api pending = create("1.00", "pending-key"); assertEquals(0, pending.code());
        assertEquals(1, reverse(original, "pending-key").code());
        assertEquals(0, balance().compareTo(BigDecimal.TEN));
        assertEquals("EFFECTIVE", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, original));
        assertEquals(0, intOf("select count(*) from station_adjustment where reverses is not null"));
        Api done = reverse(original, "real-reversal-key"); assertEquals(0, done.code());
        Api retry = reverse(original, "real-reversal-key"); assertEquals(0, retry.code());
        assertEquals(done.data().path("id"), retry.data().path("id"));
        assertEquals("EFFECTIVE", retry.data().path("status").asText());
        assertEquals(1, reverse(original, "different-reversal-key").code());
        assertEquals(0, balance().compareTo(BigDecimal.ZERO));
        assertEquals(2, intOf("select count(*) from deposit_record"));
        assertEquals(0, decimalOf("select sum(amount) from deposit_record").compareTo(BigDecimal.ZERO));
        assertEquals("PENDING", jdbc.queryForObject("select status from station_adjustment where id=?", String.class, pending.data().path("id").asLong()));
    }

    @Test void reversalKeyCannotBeReusedForAnotherOriginalOrForStandaloneCreation() {
        seed(); long first = effective("10.00", "first-original");
        long second = effective("10.00", "second-original");
        Api reversed = reverse(first, "scoped-reversal"); assertEquals(0, reversed.code());
        assertEquals(1, reverse(second, "scoped-reversal").code());
        var mirror = om.valueToTree(Map.of("customerId", customer, "adjustType", "DEPOSIT_DEDUCT",
                "amount", BigDecimal.TEN, "reason", reversed.data().path("reason").asText(), "clientToken", "scoped-reversal"));
        assertEquals(1, post("/api/manager/adjustments", manager, mirror.toString()).code());
        assertEquals(0, balance().compareTo(BigDecimal.TEN));
        assertEquals(3, intOf("select count(*) from deposit_record"));
    }

    @Test void concurrentSameKeyCreatesExactlyOneReceipt() throws Exception {
        seed(); List<Api> results = together(6, i -> create("10.00", "racing-create"));
        long id = results.get(0).data().path("id").asLong();
        for (Api result : results) { assertEquals(0, result.code(), result.toString()); assertEquals(id, result.data().path("id").asLong()); }
        assertEquals(1, intOf("select count(*) from station_adjustment"));
    }

    @Test void concurrentSameKeyDifferentContentsCannotBothSucceed() throws Exception {
        seed(); List<Api> results = together(2, i -> create(i == 0 ? "10.00" : "20.00", "racing-different"));
        assertEquals(1, results.stream().filter(Api::isSuccess).count());
        assertEquals(1, results.stream().filter(r -> r.code() == 1).count());
        assertEquals(1, intOf("select count(*) from station_adjustment"));
    }

    @Test void concurrentSameKeyReversalReturnsOneEffectiveReceipt() throws Exception {
        seed(); long original = effective("10.00", "concurrent-original");
        List<Api> results = together(2, i -> reverse(original, "concurrent-reversal"));
        for (Api result : results) assertEquals(0, result.code(), result.toString());
        assertEquals(results.get(0).data().path("id"), results.get(1).data().path("id"));
        assertEquals(2, intOf("select count(*) from station_adjustment"));
        assertEquals(2, intOf("select count(*) from deposit_record"));
        assertEquals(0, balance().compareTo(BigDecimal.ZERO));
    }

    @Test void concurrentDifferentKeyReversalCannotDeductTwice() throws Exception {
        seed(); long original = effective("10.00", "different-key-original");
        List<Api> results = together(2, i -> reverse(original, "different-key-reversal-" + i));
        assertEquals(1, results.stream().filter(Api::isSuccess).count());
        assertEquals(1, results.stream().filter(r -> r.code() == 1).count());
        assertEquals(2, intOf("select count(*) from station_adjustment"));
        assertEquals(2, intOf("select count(*) from deposit_record"));
        assertEquals(0, balance().compareTo(BigDecimal.ZERO));
    }

    @Test void customersAndDeliveryCannotCreateOrReverseStationAdjustments() {
        seed(); long original = effective("10.00", "role-original");
        String delivery = staffToken(createStaff("合成配送员", "DELIVERY", station, 1), "DELIVERY", station);
        for (String token : List.of(customerToken(customer), delivery)) {
            assertEquals(1, post("/api/manager/adjustments", token, body(customer, "10.00", "unauthorized-key")).code());
            assertEquals(1, post("/api/manager/adjustments/" + original + "/reverse", token, "{\"clientToken\":\"unauthorized-reverse\"}").code());
        }
        assertEquals(0, balance().compareTo(BigDecimal.TEN));
        assertEquals(1, intOf("select count(*) from station_adjustment"));
    }

    private List<Api> together(int count, java.util.function.IntFunction<Api> action) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), go = new CountDownLatch(1);
        try {
            List<Future<Api>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) { final int n = i; futures.add(pool.submit(() -> { ready.countDown(); if (!go.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier"); return action.apply(n); })); }
            assertTrue(ready.await(20, TimeUnit.SECONDS)); go.countDown();
            List<Api> results = new ArrayList<>(); for (Future<Api> future : futures) results.add(future.get(40, TimeUnit.SECONDS));
            return results;
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
