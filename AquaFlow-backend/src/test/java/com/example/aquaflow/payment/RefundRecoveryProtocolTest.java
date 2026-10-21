package com.example.aquaflow.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.example.aquaflow.payment.RefundRecoveryProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic requests only: no Spring context, database, credentials or channel API. */
class RefundRecoveryProtocolTest {
    private static final Request REQUEST = request(101, "binding-fixture", "original-fixture", "refund-fixture", 1234, 500, "CNY");
    private static final String REFUND = "channel-refund-fixture";

    @Test
    void unknownResultPlansQueryWithExactOriginalKeyAndPayment() {
        Snapshot initial = awaitingResult(REQUEST);
        assertEquals(Outcome.UNKNOWN, initial.outcome());
        assertNull(initial.channelRefundReference());
        assertSame(REQUEST, queryPlan(initial).orElseThrow().originalRequest());
        assertEquals("refund-fixture", queryPlan(initial).orElseThrow().originalRequest().requestKey());
        assertEquals("original-fixture", queryPlan(initial).orElseThrow().originalRequest().originalTransactionReference());
    }

    @Test
    void acceptedAcknowledgementIsNotSuccessfulRefund() {
        Transition accepted = observe(awaitingResult(REQUEST), evidence(Outcome.ACCEPTED, null));
        assertEquals(Outcome.ACCEPTED, accepted.snapshot().outcome());
        assertFalse(accepted.successBecameKnown());
        assertSame(REQUEST, queryPlan(accepted.snapshot()).orElseThrow().originalRequest());
    }

    @Test
    void processingKeepsQueryingTheOriginalRequestWithoutSettlementSignal() {
        Snapshot accepted = observe(awaitingResult(REQUEST), evidence(Outcome.ACCEPTED, REFUND)).snapshot();
        Transition processing = observe(accepted, evidence(Outcome.PROCESSING, REFUND));
        assertEquals(Outcome.PROCESSING, processing.snapshot().outcome());
        assertFalse(processing.successBecameKnown());
        assertSame(REQUEST, queryPlan(processing.snapshot()).orElseThrow().originalRequest());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"UNKNOWN", "ACCEPTED", "PROCESSING"})
    void repeatedTimeoutNeverLosesFactsOrChangesRequestKey(Outcome state) {
        Snapshot current = state == Outcome.UNKNOWN ? awaitingResult(REQUEST)
                : observe(awaitingResult(REQUEST), evidence(state, REFUND)).snapshot();
        Transition timeout = observe(current, evidence(Outcome.UNKNOWN, null));
        assertSame(current, timeout.snapshot());
        assertFalse(timeout.successBecameKnown());
        assertSame(REQUEST, queryPlan(timeout.snapshot()).orElseThrow().originalRequest());
    }

    @Test
    void successAfterUnknownResultIsRecognizedWithoutNewSubmission() {
        Transition success = observe(awaitingResult(REQUEST), evidence(Outcome.SUCCEEDED, REFUND));
        assertTrue(success.successBecameKnown());
        assertEquals(Outcome.SUCCEEDED, success.snapshot().outcome());
        assertEquals(REFUND, success.snapshot().channelRefundReference());
        assertTrue(queryPlan(success.snapshot()).isEmpty());
    }

    @Test
    void acceptedThenProcessingThenSuccessEmitsOneNewSuccessFact() {
        Snapshot current = observe(awaitingResult(REQUEST), evidence(Outcome.ACCEPTED, null)).snapshot();
        current = observe(current, evidence(Outcome.PROCESSING, REFUND)).snapshot();
        Transition success = observe(current, evidence(Outcome.SUCCEEDED, REFUND));
        assertTrue(success.successBecameKnown());
        Transition replay = observe(success.snapshot(), evidence(Outcome.SUCCEEDED, REFUND));
        assertSame(success.snapshot(), replay.snapshot());
        assertFalse(replay.successBecameKnown());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"UNKNOWN", "ACCEPTED", "PROCESSING"})
    void staleNonFinalResultCannotUndoSuccess(Outcome outcome) {
        Snapshot success = observe(awaitingResult(REQUEST), evidence(Outcome.SUCCEEDED, REFUND)).snapshot();
        Transition stale = observe(success, evidence(outcome, outcome == Outcome.UNKNOWN ? null : REFUND));
        assertSame(success, stale.snapshot());
        assertFalse(stale.successBecameKnown());
        assertTrue(queryPlan(stale.snapshot()).isEmpty());
    }

    @Test
    void definitiveFailureIsNotSuccessAndDoesNotAuthorizeAnotherAttempt() {
        Transition failed = observe(awaitingResult(REQUEST), evidence(Outcome.FAILED, REFUND));
        assertEquals(Outcome.FAILED, failed.snapshot().outcome());
        assertFalse(failed.successBecameKnown());
        assertTrue(queryPlan(failed.snapshot()).isEmpty());
        assertSame(failed.snapshot(), observe(failed.snapshot(), evidence(Outcome.FAILED, REFUND)).snapshot());
    }

    @Test
    void repeatedSuccessWithAnotherChannelIdIsConflictRatherThanAnIdempotentReplay() {
        Snapshot success = observe(awaitingResult(REQUEST), evidence(Outcome.SUCCEEDED, REFUND)).snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> observe(success, new Observation(REQUEST, Outcome.SUCCEEDED, "other-refund")));
        assertEquals(REFUND, success.channelRefundReference());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"UNKNOWN", "ACCEPTED", "PROCESSING"})
    void staleNonFinalResultCannotUndoDefinitiveFailure(Outcome outcome) {
        Snapshot failed = observe(awaitingResult(REQUEST), evidence(Outcome.FAILED, REFUND)).snapshot();
        assertSame(failed, observe(failed, evidence(outcome, outcome == Outcome.UNKNOWN ? null : REFUND)).snapshot());
    }

    @Test
    void contradictoryFinalResultsRejectWithoutChangingEitherSnapshot() {
        Snapshot success = observe(awaitingResult(REQUEST), evidence(Outcome.SUCCEEDED, REFUND)).snapshot();
        Snapshot failure = observe(awaitingResult(REQUEST), evidence(Outcome.FAILED, REFUND)).snapshot();
        assertThrows(IllegalArgumentException.class, () -> observe(success, evidence(Outcome.FAILED, REFUND)));
        assertThrows(IllegalArgumentException.class, () -> observe(failure, evidence(Outcome.SUCCEEDED, REFUND)));
        assertEquals(Outcome.SUCCEEDED, success.outcome());
        assertEquals(Outcome.FAILED, failure.outcome());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"ACCEPTED", "PROCESSING", "SUCCEEDED", "FAILED"})
    void differentChannelRefundIdCannotReplaceTheOriginal(Outcome incoming) {
        Snapshot current = observe(awaitingResult(REQUEST), evidence(Outcome.ACCEPTED, REFUND)).snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> observe(current, new Observation(REQUEST, incoming, "different-channel-refund")));
        assertEquals(REFUND, current.channelRefundReference());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "binding", "original", "key", "total", "refund"})
    void resultMustMatchEveryOriginalSnapshotFieldEvenWhenKeyLooksRight(String changed) {
        Request other = request(changed.equals("payment") ? 102 : 101,
                changed.equals("binding") ? "other-binding" : REQUEST.channelBindingReference(),
                changed.equals("original") ? "other-original" : REQUEST.originalTransactionReference(),
                changed.equals("key") ? "other-key" : REQUEST.requestKey(),
                changed.equals("total") ? 1235 : 1234, changed.equals("refund") ? 501 : 500, "CNY");
        Snapshot current = awaitingResult(REQUEST);
        assertThrows(IllegalArgumentException.class,
                () -> observe(current, new Observation(other, Outcome.SUCCEEDED, REFUND)));
        assertEquals(Outcome.UNKNOWN, current.outcome());
        assertSame(REQUEST, queryPlan(current).orElseThrow().originalRequest());
    }

    @Test
    void firstKnownChannelIdCanBeLearnedWhileStaleAcknowledgementCannotRegressProcessing() {
        Snapshot current = observe(awaitingResult(REQUEST), evidence(Outcome.PROCESSING, null)).snapshot();
        Transition learned = observe(current, evidence(Outcome.ACCEPTED, REFUND));
        assertEquals(Outcome.PROCESSING, learned.snapshot().outcome());
        assertEquals(REFUND, learned.snapshot().channelRefundReference());
        assertFalse(learned.successBecameKnown());
        assertSame(learned.snapshot(), observe(learned.snapshot(), evidence(Outcome.ACCEPTED, REFUND)).snapshot());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"SUCCEEDED", "FAILED"})
    void finalResultWithoutChannelRefundIdIsRejected(Outcome outcome) {
        assertThrows(IllegalArgumentException.class, () -> evidence(outcome, null));
    }

    @Test
    void unknownObservationCannotIntroduceUnverifiedChannelId() {
        assertThrows(IllegalArgumentException.class, () -> evidence(Outcome.UNKNOWN, REFUND));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1235, Long.MAX_VALUE})
    void nonPositiveOrExcessiveRefundAmountsAreRejected(long amount) {
        assertThrows(IllegalArgumentException.class, () -> request(101, "b", "t", "k", 1234, amount, "CNY"));
    }

    @Test
    void integerCentsRemainExactAtLongBoundaryWithoutRoundingOrOverflow() {
        Request max = request(101, "b", "t", "k", Long.MAX_VALUE, Long.MAX_VALUE, "CNY");
        Snapshot success = observe(awaitingResult(max), new Observation(max, Outcome.SUCCEEDED, REFUND)).snapshot();
        assertEquals(Long.MAX_VALUE, success.request().refundAmountCents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "with space", "with\nnewline", "中文", "with\tTab"})
    void opaqueReferencesCannotBeBlankControlCharactersOrAmbiguousTokens(String value) {
        assertThrows(IllegalArgumentException.class, () -> request(101, value, "t", "k", 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(101, "b", value, "k", 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(101, "b", "t", value, 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> evidence(Outcome.ACCEPTED, value));
    }

    @Test
    void boundedOpaqueReferencesAndServerSnapshotCannotBeMissing() {
        assertThrows(IllegalArgumentException.class, () -> request(0, "b", "t", "k", 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(1, null, "t", "k", 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", null, "k", 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", "t", null, 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", "t", "k", 0, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", "t", "k", 10, 1, null));
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", "t", "x".repeat(129), 10, 1, "CNY"));
        assertThrows(IllegalArgumentException.class, () -> evidence(Outcome.ACCEPTED, "x".repeat(129)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "cny", "CNY ", ""})
    void currencyCannotBeChangedOrGuessed(String currency) {
        assertThrows(IllegalArgumentException.class, () -> request(1, "b", "t", "k", 10, 1, currency));
    }

    @Test
    void nullProtocolInputsRejectBeforeAnyTransition() {
        Snapshot current = awaitingResult(REQUEST);
        assertThrows(IllegalArgumentException.class, () -> awaitingResult(null));
        assertThrows(IllegalArgumentException.class, () -> queryPlan(null));
        assertThrows(IllegalArgumentException.class, () -> observe(null, evidence(Outcome.ACCEPTED, null)));
        assertThrows(IllegalArgumentException.class, () -> observe(current, null));
        assertThrows(IllegalArgumentException.class, () -> new Observation(null, Outcome.ACCEPTED, null));
        assertThrows(IllegalArgumentException.class, () -> new Observation(REQUEST, null, null));
    }

    @Test
    void equivalentSnapshotDoesNotReplaceTheOriginalRequestObject() {
        Request same = request(101, "binding-fixture", "original-fixture", "refund-fixture", 1234, 500, "CNY");
        Snapshot next = observe(awaitingResult(REQUEST), new Observation(same, Outcome.PROCESSING, REFUND)).snapshot();
        assertSame(REQUEST, next.request());
        assertSame(REQUEST, queryPlan(next).orElseThrow().originalRequest());
    }

    @Test
    void knowledgeSnapshotsAreIndependentAndDoNotPretendToProvideDurableConcurrencyControl() {
        Snapshot initial = awaitingResult(REQUEST);
        Transition first = observe(initial, evidence(Outcome.SUCCEEDED, REFUND));
        Transition parallel = observe(initial, evidence(Outcome.SUCCEEDED, REFUND));
        assertTrue(first.successBecameKnown());
        assertTrue(parallel.successBecameKnown());
        assertEquals(Outcome.UNKNOWN, initial.outcome());
        assertFalse(observe(first.snapshot(), evidence(Outcome.SUCCEEDED, REFUND)).successBecameKnown());
    }

    @Test
    void diagnosticStringsAndExceptionsDoNotExposeRequestOrChannelIdentifiers() {
        Snapshot current = observe(awaitingResult(REQUEST), evidence(Outcome.PROCESSING, REFUND)).snapshot();
        String message = assertThrows(IllegalArgumentException.class,
                () -> observe(current, new Observation(REQUEST, Outcome.SUCCEEDED, "other-refund"))).getMessage();
        String diagnostic = REQUEST + " " + evidence(Outcome.PROCESSING, REFUND) + " " + current
                + " " + queryPlan(current).orElseThrow() + " " + message;
        for (String secret : new String[]{REQUEST.channelBindingReference(), REQUEST.originalTransactionReference(), REQUEST.requestKey(), REFUND, "other-refund"}) {
            assertFalse(diagnostic.contains(secret));
        }
    }

    private static Request request(long payment, String binding, String original, String key, long total, long amount, String currency) {
        return new Request(payment, binding, original, key, total, amount, currency);
    }

    private static Observation evidence(Outcome outcome, String reference) {
        return new Observation(REQUEST, outcome, reference);
    }
}
