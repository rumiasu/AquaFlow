package com.example.aquaflow.payment.wechat;

import com.example.aquaflow.constant.PayMethod;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 已验签通知与服务端预期快照的纯匹配，不查询数据库、不确认支付或执行入账。
 * 只支持普通商户 JSAPI、明确带子 AppID 的服务商 JSAPI；不推断通知所属模式。
 * TODO(待拍板)：站级商户/AppID 主体绑定见 docs/design/16 §9.3 C-01，协议支持不等于选定经营关系。
 */
public final class WechatPaySuccessMatcher {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public MatchedPayment match(WechatPayNotificationAdapter.VerifiedNotification verified, ExpectedPayment expected) {
        try {
            if (verified == null || expected == null || expected.paymentRecordId() <= 0
                    || (expected.orderId() != null && expected.orderId() <= 0)
                    || expected.paymentMethod() != PayMethod.WECHAT
                    || !"CNY".equals(expected.currency()) || !token(expected.outTradeNo(), 32)
                    || (expected.knownTransactionId() != null && !token(expected.knownTransactionId(), 32))) {
                throw new RejectedMatchException();
            }
            int expectedCents = expectedCents(expected.amountYuan());
            JsonNode envelope = object(JSON.readTree(verified.rawBody()));
            if (!"TRANSACTION.SUCCESS".equals(text(envelope, "event_type", 64))
                    || !"encrypt-resource".equals(text(envelope, "resource_type", 64))
                    || !"transaction".equals(text(object(envelope.get("resource")), "original_type", 64))) {
                throw new RejectedMatchException();
            }
            String notificationId = text(envelope, "id", 64);
            JsonNode transaction = object(JSON.readTree(verified.resourceJson()));
            if (!"SUCCESS".equals(text(transaction, "trade_state", 32))
                    || !"JSAPI".equals(text(transaction, "trade_type", 32))) {
                throw new RejectedMatchException();
            }
            checkMerchant(transaction, expected.merchant());
            if (!expected.outTradeNo().equals(text(transaction, "out_trade_no", 32))) {
                throw new RejectedMatchException();
            }
            String transactionId = text(transaction, "transaction_id", 32);
            if (expected.knownTransactionId() != null && !expected.knownTransactionId().equals(transactionId)) {
                throw new RejectedMatchException();
            }
            JsonNode amount = object(transaction.get("amount"));
            int total = cents(amount.get("total"));
            if (total <= 0 || total != expectedCents || !"CNY".equals(text(amount, "currency", 3))) {
                throw new RejectedMatchException();
            }
            // 两个金额分别保留为协议事实；实际实收/优惠记账仍由后续业务编排确定。
            Integer payerTotal = null;
            if (amount.has("payer_total")) {
                payerTotal = cents(amount.get("payer_total"));
                if (payerTotal < 0 || payerTotal > total) {
                    throw new RejectedMatchException();
                }
            }
            if (amount.has("payer_currency") && !"CNY".equals(text(amount, "payer_currency", 3))) {
                throw new RejectedMatchException();
            }
            return new MatchedPayment(expected, notificationId, transactionId, total, payerTotal);
        } catch (IOException | RuntimeException failure) {
            // 不附带 Jackson/字段比较异常，防止原文、客户信息或业务快照进入错误日志。
            throw new RejectedMatchException();
        }
    }

    private static void checkMerchant(JsonNode transaction, MerchantIdentity expected) {
        if (expected instanceof DirectJsapi direct) {
            if (transaction.has("sp_mchid") || transaction.has("sp_appid")
                    || transaction.has("sub_mchid") || transaction.has("sub_appid")) {
                throw new RejectedMatchException();
            }
            same(transaction, "mchid", direct.merchantId());
            same(transaction, "appid", direct.appId());
        } else if (expected instanceof PartnerJsapiWithSubApp partner) {
            if (transaction.has("mchid") || transaction.has("appid")) {
                throw new RejectedMatchException();
            }
            same(transaction, "sp_mchid", partner.serviceProviderMerchantId());
            same(transaction, "sp_appid", partner.serviceProviderAppId());
            same(transaction, "sub_mchid", partner.subMerchantId());
            // 不将缺省 sub_appid 补成 sp_appid；服务商不带子 AppID 的变体尚不支持。
            same(transaction, "sub_appid", partner.subAppId());
        } else {
            throw new RejectedMatchException();
        }
    }

    private static void same(JsonNode transaction, String name, String expected) {
        if (!token(expected, 32) || !expected.equals(text(transaction, name, 32))) {
            throw new RejectedMatchException();
        }
    }

    private static JsonNode object(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new RejectedMatchException();
        }
        return node;
    }

    private static String text(JsonNode parent, String name, int maximumLength) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || !token(value.textValue(), maximumLength)) {
            throw new RejectedMatchException();
        }
        return value.textValue();
    }

    private static boolean token(String value, int maximumLength) {
        if (value == null || value.isEmpty() || value.length() > maximumLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x20 || character >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static int expectedCents(BigDecimal amountYuan) {
        if (amountYuan == null || amountYuan.signum() <= 0) {
            throw new RejectedMatchException();
        }
        // 元转分只接受精确结果；不四舍五入，也不经过 double。
        return amountYuan.movePointRight(2).intValueExact();
    }

    private static int cents(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new RejectedMatchException();
        }
        return node.intValue();
    }

    public sealed interface MerchantIdentity permits DirectJsapi, PartnerJsapiWithSubApp { }

    public record DirectJsapi(String merchantId, String appId) implements MerchantIdentity { }

    public record PartnerJsapiWithSubApp(String serviceProviderMerchantId, String serviceProviderAppId,
                                        String subMerchantId, String subAppId) implements MerchantIdentity { }

    /**
     * 必须由服务端查得的真实微信流水和渠道绑定构建，不能复制回调字段作为预期。
     * amountYuan 对应 payment_record.amount；orderId 可空（直购水票）。outTradeNo 须另有持久映射，
     * 现库尚未保存该映射，本组件不猜成 payment_record.id 或 orders.id。
     * knownTransactionId 为服务端已知的微信交易号；首次通知未知时可空，但返回的号仍须后续持久防重。
     */
    public record ExpectedPayment(long paymentRecordId, Long orderId, int paymentMethod, MerchantIdentity merchant,
                                  String outTradeNo, BigDecimal amountYuan, String currency, String knownTransactionId) {
        @Override
        public String toString() { return "ExpectedWechatPayment[redacted]"; }
    }

    /** 只有匹配器能创建；匹配通过仍须后续持久幂等和业务 CAS，不表示已经入账。 */
    public static final class MatchedPayment {
        private final ExpectedPayment expected;
        private final String notificationId;
        private final String transactionId;
        private final int totalCents;
        private final Integer payerTotalCents;

        private MatchedPayment(ExpectedPayment expected, String notificationId, String transactionId, int totalCents, Integer payerTotalCents) {
            this.expected = expected;
            this.notificationId = notificationId;
            this.transactionId = transactionId;
            this.totalCents = totalCents;
            this.payerTotalCents = payerTotalCents;
        }

        public ExpectedPayment expected() { return expected; }
        public String notificationId() { return notificationId; }
        public String transactionId() { return transactionId; }
        public int totalCents() { return totalCents; }
        public Integer payerTotalCents() { return payerTotalCents; }
        @Override
        public String toString() { return "MatchedWechatPayment[redacted]"; }
    }

    public static final class RejectedMatchException extends RuntimeException {
        private RejectedMatchException() { super("微信支付回调与预期支付不匹配"); }
    }
}
