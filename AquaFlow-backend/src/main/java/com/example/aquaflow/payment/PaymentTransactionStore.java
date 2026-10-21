package com.example.aquaflow.payment;

import java.util.Optional;

/**
 * Storage contract only: no production implementation, mapper, schema change or registered bean.
 * See docs/design/35 section 13 for required uniqueness, atomicity and real-database validation.
 */
public interface PaymentTransactionStore {
    /**
     * Atomically reserve an unconfirmed original request before any future channel submission.
     * One payment record and (binding reference, outTradeNo) must each identify the same immutable request.
     * Exact replay returns the current identity, including any subsequently known transaction id.
     * Conflicting reuse is rejected without partial index/row writes; a preconfirmed proposal is rejected.
     */
    PaymentTransactionIdentity reserve(PaymentTransactionIdentity originalRequest);

    /** Server-only lookup; neither method authorizes customer/station access or resolves a merchant. */
    Optional<PaymentTransactionIdentity> findByPaymentRecord(long paymentRecordId);

    /** The binding reference is an opaque server namespace, never a station id inferred from a callback. */
    Optional<PaymentTransactionIdentity> findByRequest(String channelBindingReference, String outTradeNo);

    /**
     * Atomically bind a verified transaction to an already reserved matching request.
     * Stale unconfirmed snapshots may replay the same result; conflicting known ids and transaction reuse
     * within the same binding namespace must reject without changing any original identity.
     * Does not confirm payment, refund, publish notifications or mutate money/assets.
     */
    PaymentTransactionIdentity bindVerifiedTransaction(PaymentTransactionIdentity expectedOriginal, String transactionId);
}
