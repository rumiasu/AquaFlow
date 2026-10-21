package com.example.aquaflow.payment;

import java.util.Objects;
import java.util.Optional;

/**
 * Offline refund-result contract; no channel transport, Spring registration or ledger writes.
 * See docs/design/35 section 12 for trust, persistence and activation boundaries.
 */
public final class RefundRecoveryProtocol {
    private RefundRecoveryProtocol() { }

    /** Channel-neutral observations, not a mapping of HTTP codes or vendor status strings. */
    public enum Outcome { UNKNOWN, ACCEPTED, PROCESSING, SUCCEEDED, FAILED }

    /**
     * Immutable server-authorized request. The binding reference is opaque: it selects no merchant mode.
     * This validates one request's shape, not refund eligibility or the cumulative refundable balance.
     */
    public record Request(long paymentRecordId, String channelBindingReference,
                          String originalTransactionReference, String requestKey,
                          long originalAmountCents, long refundAmountCents, String currency) {
        public Request {
            if (paymentRecordId <= 0 || !token(channelBindingReference)
                    || !token(originalTransactionReference) || !token(requestKey)
                    || originalAmountCents <= 0 || refundAmountCents <= 0
                    || refundAmountCents > originalAmountCents || !"CNY".equals(currency)) {
                throw rejected();
            }
        }

        @Override public String toString() { return "RefundRequest[redacted]"; }
    }

    /**
     * Must come from a future verified/matched channel adapter. A timeout or parse failure is UNKNOWN,
     * never FAILED. FAILED means a definitive final channel result, not ABNORMAL or an HTTP error.
     * No vendor status mapping or merchant resolution is provided by this class.
     */
    public record Observation(Request request, Outcome outcome, String channelRefundReference) {
        public Observation {
            if (request == null || outcome == null
                    || (channelRefundReference != null && !token(channelRefundReference))
                    || ((outcome == Outcome.SUCCEEDED || outcome == Outcome.FAILED)
                        && channelRefundReference == null)
                    || (outcome == Outcome.UNKNOWN && channelRefundReference != null)) {
                throw rejected();
            }
        }

        @Override public String toString() { return "RefundObservation[redacted]"; }
    }

    /** Immutable in-memory knowledge, not a persisted payment or refund status. */
    public static final class Snapshot {
        private final Request request;
        private final Outcome outcome;
        private final String channelRefundReference;

        private Snapshot(Request request, Outcome outcome, String channelRefundReference) {
            this.request = request;
            this.outcome = outcome;
            this.channelRefundReference = channelRefundReference;
        }

        public Request request() { return request; }
        public Outcome outcome() { return outcome; }
        public String channelRefundReference() { return channelRefundReference; }
        @Override public String toString() { return "RefundSnapshot[" + outcome + ", redacted]"; }
    }

    /**
     * A new success fact, not permission to settle money. Future persistence must CAS the snapshot and
     * write any authorized ledger effect in one transaction; parallel copies alone provide no exactly-once guarantee.
     */
    public static final class Transition {
        private final Snapshot snapshot;
        private final boolean successBecameKnown;

        private Transition(Snapshot snapshot, boolean successBecameKnown) {
            this.snapshot = snapshot;
            this.successBecameKnown = successBecameKnown;
        }

        public Snapshot snapshot() { return snapshot; }
        public boolean successBecameKnown() { return successBecameKnown; }
    }

    /** Data-only query plan. It cannot execute or create another refund request. */
    public static final class QueryPlan {
        private final Request originalRequest;

        private QueryPlan(Request originalRequest) { this.originalRequest = originalRequest; }
        public Request originalRequest() { return originalRequest; }
        @Override public String toString() { return "RefundQueryPlan[redacted]"; }
    }

    /** Called after a refund attempt whose result has not yet been established. Does not submit it. */
    public static Snapshot awaitingResult(Request originalRequest) {
        if (originalRequest == null) throw rejected();
        return new Snapshot(originalRequest, Outcome.UNKNOWN, null);
    }

    /** Unresolved outcomes always query the exact original request, never a newly generated key. */
    public static Optional<QueryPlan> queryPlan(Snapshot snapshot) {
        if (snapshot == null) throw rejected();
        return terminal(snapshot.outcome) ? Optional.empty()
                : Optional.of(new QueryPlan(snapshot.request));
    }

    /**
     * Merge a matched result without regressing established facts. Conflicting identities, amounts or
     * terminal results are rejected; the caller must not turn that rejection into a new refund attempt.
     */
    public static Transition observe(Snapshot current, Observation observation) {
        if (current == null || observation == null || !current.request.equals(observation.request)) {
            throw rejected();
        }
        String incomingReference = observation.channelRefundReference;
        if (current.channelRefundReference != null && incomingReference != null
                && !current.channelRefundReference.equals(incomingReference)) {
            throw rejected();
        }
        Outcome incoming = observation.outcome;
        if (terminal(current.outcome)) {
            if (terminal(incoming) && incoming != current.outcome) throw rejected();
            return new Transition(current, false);
        }
        // [2026-10-21] Accepted/processing is not refunded. Late timeouts and stale acknowledgements
        // must not erase stronger knowledge or cause a second submission with a fresh request key.
        Outcome next = incoming == Outcome.UNKNOWN
                || (incoming == Outcome.ACCEPTED && current.outcome == Outcome.PROCESSING)
                ? current.outcome : incoming;
        String reference = current.channelRefundReference != null
                ? current.channelRefundReference : incomingReference;
        if (next == current.outcome && Objects.equals(reference, current.channelRefundReference)) {
            return new Transition(current, false);
        }
        return new Transition(new Snapshot(current.request, next, reference), next == Outcome.SUCCEEDED);
    }

    private static boolean terminal(Outcome outcome) {
        return outcome == Outcome.SUCCEEDED || outcome == Outcome.FAILED;
    }

    // Opaque local references are bounded ASCII tokens, not claimed vendor identifier limits.
    private static boolean token(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return false;
        }
        return true;
    }

    private static IllegalArgumentException rejected() {
        // Do not include original request, identifiers or channel errors in exception/log output.
        return new IllegalArgumentException("Refund recovery evidence is invalid or conflicts with the original request");
    }
}
