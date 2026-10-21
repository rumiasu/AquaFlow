package com.example.aquaflow.payment;

import com.example.aquaflow.payment.wechat.WechatPaySuccessMatcher;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Server-owned original-payment identity for a future store adapter; no merchant-mode resolution.
 * See docs/design/35 section 13. References and transaction identifiers must never be logged.
 */
public record PaymentTransactionIdentity(long paymentRecordId, Long orderId, String channelBindingReference,
                                         String outTradeNo, BigDecimal amountYuan, String currency,
                                         String knownTransactionId) {
    public PaymentTransactionIdentity {
        if (paymentRecordId <= 0 || (orderId != null && orderId <= 0)
                || !token(channelBindingReference, 128) || !token(outTradeNo, 32)
                || !"CNY".equals(currency)
                || (knownTransactionId != null && !token(knownTransactionId, 32))) throw rejected();
        try {
            int cents = amountYuan.movePointRight(2).intValueExact();
            if (cents <= 0) throw rejected();
            // Existing matcher uses exact int cents. Normalize scale so 12.34 and 12.3400 replay alike.
            amountYuan = BigDecimal.valueOf(cents, 2);
        } catch (ArithmeticException | NullPointerException failure) {
            throw rejected();
        }
    }

    /** Bind only a verified channel transaction; repeating it is harmless, replacing it is a conflict. */
    public PaymentTransactionIdentity withVerifiedTransaction(String transactionId) {
        if (!token(transactionId, 32)
                || (knownTransactionId != null && !knownTransactionId.equals(transactionId))) throw rejected();
        return knownTransactionId != null ? this : new PaymentTransactionIdentity(paymentRecordId, orderId,
                channelBindingReference, outTradeNo, amountYuan, currency, transactionId);
    }

    /** Compare immutable original request fields; the known channel result may have arrived meanwhile. */
    public boolean sameRequest(PaymentTransactionIdentity other) {
        return other != null && paymentRecordId == other.paymentRecordId && Objects.equals(orderId, other.orderId)
                && channelBindingReference.equals(other.channelBindingReference) && outTradeNo.equals(other.outTradeNo)
                && amountYuan.equals(other.amountYuan) && currency.equals(other.currency);
    }

    /**
     * Reuse the existing matcher with a merchant identity resolved separately from trusted server config.
     * The binding reference is not a merchant id, and callback fields must not supply this argument.
     */
    public WechatPaySuccessMatcher.ExpectedPayment forMatching(WechatPaySuccessMatcher.MerchantIdentity merchant) {
        if (merchant == null) throw rejected();
        return new WechatPaySuccessMatcher.ExpectedPayment(paymentRecordId, orderId,
                com.example.aquaflow.constant.PayMethod.WECHAT, merchant, outTradeNo,
                amountYuan, currency, knownTransactionId);
    }

    @Override public String toString() { return "PaymentTransactionIdentity[redacted]"; }

    private static boolean token(String value, int maximumLength) {
        if (value == null || value.isEmpty() || value.length() > maximumLength) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return false;
        }
        return true;
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("Original payment transaction identity is invalid or conflicts");
    }
}
