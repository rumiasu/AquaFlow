package com.example.aquaflow.payment;

import com.example.aquaflow.payment.wechat.WechatPaySuccessMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic conformance fixture only; it is not a JDBC adapter or proof of database concurrency. */
class PaymentTransactionStoreContractTest {
    private static final PaymentTransactionIdentity ORIGINAL = identity(101, 201L, "binding-A", "request-A", "12.34", null);

    @Test
    void exactReservationReplayReturnsTheOriginalAndBothLookupsAgree() {
        var store = new MemoryStore();
        assertSame(ORIGINAL, store.reserve(ORIGINAL));
        var replay = identity(101, 201L, "binding-A", "request-A", "12.3400", null);
        assertEquals(ORIGINAL, store.reserve(replay));
        assertEquals(ORIGINAL, store.findByPaymentRecord(101).orElseThrow());
        assertEquals(ORIGINAL, store.findByRequest("binding-A", "request-A").orElseThrow());
        assertTrue(store.findByRequest("binding-B", "request-A").isEmpty());
        assertTrue(store.findByPaymentRecord(999).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "binding", "request", "amount"})
    void onePaymentRecordCannotBeRemappedToDifferentOriginalFacts(String changed) {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        var conflicting = identity(101, changed.equals("order") ? 202L : 201L,
                changed.equals("binding") ? "binding-B" : "binding-A",
                changed.equals("request") ? "request-B" : "request-A", changed.equals("amount") ? "12.35" : "12.34", null);
        assertThrows(IllegalArgumentException.class, () -> store.reserve(conflicting));
        assertSame(ORIGINAL, store.findByPaymentRecord(101).orElseThrow());
        assertTrue(store.findByRequest("binding-B", "request-A").isEmpty());
        assertTrue(store.findByRequest("binding-A", "request-B").isEmpty());
    }

    @Test
    void sameScopedRequestCannotBeClaimedByAnotherPaymentRecord() {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        assertThrows(IllegalArgumentException.class,
                () -> store.reserve(identity(102, 202L, "binding-A", "request-A", "12.34", null)));
        assertTrue(store.findByPaymentRecord(102).isEmpty());
    }

    @Test
    void bindingNamespacesStayExplicitWithoutChoosingMerchantOrStationMappings() {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        var other = identity(102, 202L, "binding-B", "request-A", "12.34", null);
        store.reserve(other);
        assertEquals(101, store.findByRequest("binding-A", "request-A").orElseThrow().paymentRecordId());
        assertEquals(102, store.findByRequest("binding-B", "request-A").orElseThrow().paymentRecordId());
    }

    @Test
    void verifiedTransactionIsBoundOnceAndStaleOriginalCanReplayIt() {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        var bound = store.bindVerifiedTransaction(ORIGINAL, "tx-A");
        assertEquals("tx-A", bound.knownTransactionId());
        assertSame(bound, store.bindVerifiedTransaction(ORIGINAL, "tx-A"));
        assertSame(bound, store.reserve(ORIGINAL));
        assertSame(bound, store.findByRequest("binding-A", "request-A").orElseThrow());
        assertNull(ORIGINAL.knownTransactionId());
    }

    @Test
    void existingTransactionCannotBeReplaced() {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        var bound = store.bindVerifiedTransaction(ORIGINAL, "tx-A");
        assertThrows(IllegalArgumentException.class, () -> store.bindVerifiedTransaction(ORIGINAL, "tx-B"));
        assertSame(bound, store.findByPaymentRecord(101).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> bound.withVerifiedTransaction("tx-B"));
    }

    @Test
    void aChannelTransactionCannotBindTwoOriginalPaymentsInTheSameNamespace() {
        var store = new MemoryStore(); store.reserve(ORIGINAL);
        var other = identity(102, 202L, "binding-A", "request-B", "12.34", null);store.reserve(other);
        store.bindVerifiedTransaction(ORIGINAL, "tx-A");
        assertThrows(IllegalArgumentException.class, () -> store.bindVerifiedTransaction(other, "tx-A"));
        assertNull(store.findByPaymentRecord(102).orElseThrow().knownTransactionId());
    }

    @Test
    void bindingRequiresReservedAndMatchingOriginalFacts() {
        var store = new MemoryStore();
        assertThrows(IllegalArgumentException.class, () -> store.bindVerifiedTransaction(ORIGINAL, "tx-A"));
        store.reserve(ORIGINAL);
        var wrong = identity(101, 201L, "binding-A", "request-A", "12.35", null);
        assertThrows(IllegalArgumentException.class, () -> store.bindVerifiedTransaction(wrong, "tx-A"));
        assertNull(store.findByPaymentRecord(101).orElseThrow().knownTransactionId());
    }

    @Test
    void preconfirmedReservationCannotBypassTheSeparateVerifiedBindingStep() {
        var store = new MemoryStore();
        assertThrows(IllegalArgumentException.class, () -> store.reserve(ORIGINAL.withVerifiedTransaction("tx-A")));
        assertTrue(store.findByPaymentRecord(101).isEmpty());
    }

    @Test
    void syntheticCommitFailureLeavesNoHalfReservationOrBinding() {
        var store = new MemoryStore();store.failNextCommit = true;
        assertThrows(IllegalStateException.class, () -> store.reserve(ORIGINAL));
        assertTrue(store.findByPaymentRecord(101).isEmpty());
        assertTrue(store.findByRequest("binding-A", "request-A").isEmpty());
        store.reserve(ORIGINAL);store.failNextCommit = true;
        assertThrows(IllegalStateException.class, () -> store.bindVerifiedTransaction(ORIGINAL, "tx-A"));
        assertSame(ORIGINAL, store.findByPaymentRecord(101).orElseThrow());
        assertEquals("tx-A", store.bindVerifiedTransaction(ORIGINAL, "tx-A").knownTransactionId());
    }

    @Test
    void syntheticConcurrentReservationsAndDuplicateResultsConvergeToOneIdentity() throws Exception {
        var store = new MemoryStore();
        runTogether(() -> {store.reserve(ORIGINAL);return store.bindVerifiedTransaction(ORIGINAL, "tx-A");});
        assertEquals(1, store.rows.size());
        assertEquals("tx-A", store.findByPaymentRecord(101).orElseThrow().knownTransactionId());
    }

    @Test
    void existingMatcherReceivesServerIdentityIncludingOrderlessPayment() {
        var direct = new WechatPaySuccessMatcher.DirectJsapi("merchant-fixture", "wx-fixture");
        var expected = ORIGINAL.withVerifiedTransaction("tx-A").forMatching(direct);
        assertEquals(101, expected.paymentRecordId());assertEquals(201L, expected.orderId());
        assertSame(direct, expected.merchant());assertEquals("request-A", expected.outTradeNo());
        assertEquals(new BigDecimal("12.34"), expected.amountYuan());assertEquals("tx-A", expected.knownTransactionId());
        var orderless = identity(102, null, "binding-A", "request-B", "12.34", null);
        assertNull(orderless.forMatching(direct).orderId());
        assertThrows(IllegalArgumentException.class, () -> ORIGINAL.forMatching(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "12.341", "21474836.48"})
    void originalAmountsMustMatchExistingMatchersExactPositiveIntCents(String amount) {
        assertThrows(IllegalArgumentException.class, () -> identity(101, 201L, "b", "o", amount, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "with\nnewline", "中文"})
    void opaqueRequestAndKnownTransactionReferencesRejectAmbiguity(String value) {
        assertThrows(IllegalArgumentException.class, () -> identity(101, 201L, value, "o", "1", null));
        assertThrows(IllegalArgumentException.class, () -> identity(101, 201L, "b", value, "1", null));
        assertThrows(IllegalArgumentException.class, () -> ORIGINAL.withVerifiedTransaction(value));
    }

    @Test
    void invalidIdentityAndOversizedReferencesRejectWithoutLoggingValues() {
        assertThrows(IllegalArgumentException.class, () -> identity(0, 201L, "b", "o", "1", null));
        assertThrows(IllegalArgumentException.class, () -> identity(101, 0L, "b", "o", "1", null));
        assertThrows(IllegalArgumentException.class, () -> identity(101, 201L, "b", "x".repeat(33), "1", null));
        assertThrows(IllegalArgumentException.class, () -> identity(101, 201L, "x".repeat(129), "o", "1", null));
        assertThrows(IllegalArgumentException.class, () -> ORIGINAL.withVerifiedTransaction(null));
        var known=ORIGINAL.withVerifiedTransaction("tx-fixture");
        String message=assertThrows(IllegalArgumentException.class, () -> known.withVerifiedTransaction("other-fixture")).getMessage();
        for(String value:List.of("binding-A","request-A","tx-fixture","other-fixture")) {
            assertFalse((known+message).contains(value));
        }
    }

    @Test
    void missingAmountUnsupportedCurrencyAndMalformedKnownIdCannotEnterTheIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentTransactionIdentity(101, null, "b", "o", null, "CNY", null));
        for (String currency : new String[]{null, "USD", "cny", "CNY "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PaymentTransactionIdentity(101, null, "b", "o", BigDecimal.ONE, currency, null));
        }
        assertThrows(IllegalArgumentException.class,
                () -> identity(101, null, "b", "o", "1", "x".repeat(33)));
        assertThrows(IllegalArgumentException.class,
                () -> identity(101, null, "b", "o", "1", "tx with space"));
    }

    private static PaymentTransactionIdentity identity(long id, Long order, String binding, String request, String amount, String tx) {
        return new PaymentTransactionIdentity(id, order, binding, request, new BigDecimal(amount), "CNY", tx);
    }

    private static void runTogether(Callable<PaymentTransactionIdentity> task) throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var go = new CountDownLatch(1);
        try {
            List<Future<PaymentTransactionIdentity>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (var f : results) assertEquals("tx-A", f.get(10, TimeUnit.SECONDS).knownTransactionId());
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    /** Contract fixture: synchronized maps simulate an atomic store, never a production database. */
    private static final class MemoryStore implements PaymentTransactionStore {
        private final Map<Long, PaymentTransactionIdentity> rows = new HashMap<>();
        private boolean failNextCommit;

        @Override public synchronized PaymentTransactionIdentity reserve(PaymentTransactionIdentity proposed) {
            if (proposed == null || proposed.knownTransactionId() != null) throw new IllegalArgumentException("unconfirmed request required");
            var existing = rows.get(proposed.paymentRecordId());
            if (existing != null) {
                if (!existing.sameRequest(proposed)) throw new IllegalArgumentException("conflicting original request");
                return existing;
            }
            if (findByRequest(proposed.channelBindingReference(), proposed.outTradeNo()).isPresent()) throw new IllegalArgumentException("request already reserved");
            commit(proposed);
            return proposed;
        }

        @Override public synchronized Optional<PaymentTransactionIdentity> findByPaymentRecord(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public synchronized Optional<PaymentTransactionIdentity> findByRequest(String binding, String request) {
            return rows.values().stream().filter(r -> r.channelBindingReference().equals(binding) && r.outTradeNo().equals(request)).findFirst();
        }

        @Override public synchronized PaymentTransactionIdentity bindVerifiedTransaction(PaymentTransactionIdentity expected, String tx) {
            if (expected == null) throw new IllegalArgumentException("original request required");
            var current = rows.get(expected.paymentRecordId());
            if (current == null || !current.sameRequest(expected)) throw new IllegalArgumentException("reserved original request required");
            expected.withVerifiedTransaction(tx);
            var next = current.withVerifiedTransaction(tx);
            boolean reused = rows.values().stream().anyMatch(r -> r.paymentRecordId() != current.paymentRecordId()
                    && r.channelBindingReference().equals(current.channelBindingReference()) && tx.equals(r.knownTransactionId()));
            if (reused) throw new IllegalArgumentException("transaction already bound");
            if (next != current) commit(next);
            return next;
        }

        private void commit(PaymentTransactionIdentity value) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new IllegalStateException("synthetic commit failure");
            }
            rows.put(value.paymentRecordId(), value);
        }
    }
}
