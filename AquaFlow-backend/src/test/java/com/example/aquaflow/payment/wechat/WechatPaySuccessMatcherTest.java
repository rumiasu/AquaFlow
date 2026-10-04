package com.example.aquaflow.payment.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.aquaflow.constant.PayMethod;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Every callback reaches the matcher through real SDK verification using in-memory test keys. */
class WechatPaySuccessMatcherTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TX = "420000000000000000000001";
    private static final String SERIAL = "PUB_KEY_ID_match_fixture";
    private static final String TIMESTAMP = "1791000000";
    private static final String HEADER_NONCE = "match-fixture";
    private static final WechatPaySuccessMatcher.DirectJsapi DIRECT = new WechatPaySuccessMatcher.DirectJsapi("merchant-A", "wx-app-A");
    private static final WechatPaySuccessMatcher.PartnerJsapiWithSubApp PARTNER = new WechatPaySuccessMatcher.PartnerJsapiWithSubApp("provider-A", "wx-provider-A", "sub-merchant-A", "wx-sub-A");
    private static final WechatPaySuccessMatcher.ExpectedPayment EXPECTED = expected(DIRECT, new BigDecimal("12.34"), null);
    private static final WechatPaySuccessMatcher MATCHER = new WechatPaySuccessMatcher();
    private static KeyPair keyPair;
    private static String apiKey;
    private static WechatPayNotificationAdapter adapter;

    @BeforeAll
    static void keysOnlyInMemory() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        apiKey = HexFormat.of().formatHex(key);
        adapter = new WechatPayNotificationAdapter(keyPair.getPublic(), SERIAL, apiKey,
                Clock.fixed(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), ZoneOffset.UTC));
    }

    @Test
    void directPaymentMatchesServerSnapshotAndUsesGrossTotalDespiteDiscount() throws Exception {
        var result = MATCHER.match(verified(direct()), EXPECTED);
        assertSame(EXPECTED, result.expected());
        assertEquals("event-fixture", result.notificationId());
        assertEquals(TX, result.transactionId());
        assertEquals(1234, result.totalCents());
        assertEquals(1200, result.payerTotalCents());
        assertFalse(result.toString().contains(TX));
        assertFalse(EXPECTED.toString().contains("merchant-A"));
    }

    @Test
    void explicitPartnerSubAppProfileMatchesAllFourIdentities() throws Exception {
        var result = MATCHER.match(verified(partner()), expected(PARTNER, new BigDecimal("12.34"), TX));
        assertEquals(1234, result.totalCents());
        assertEquals(PARTNER, result.expected().merchant());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mchid", "appid", "out_trade_no", "transaction_id", "trade_state", "trade_type", "amount", "amount.total", "amount.currency"})
    void criticalTransactionFieldsCannotBeMissingOrNull(String path) throws Exception {
        for (boolean missing : new boolean[]{true, false}) {
            ObjectNode tx = direct();
            String[] parts = path.split("\\.");
            ObjectNode parent = parts.length == 2 ? (ObjectNode) tx.get(parts[0]) : tx;
            String field = parts[parts.length - 1];
            if (missing) parent.remove(field); else parent.putNull(field);
            rejected(verified(tx), EXPECTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mchid", "appid", "out_trade_no", "transaction_id", "trade_state", "trade_type", "amount.currency"})
    void criticalTextFieldsCannotCoerceNumbersBooleansObjectsOrArrays(String path) throws Exception {
        for (String value : new String[]{"1234", "true", "{}", "[]", "\" \""}) {
            ObjectNode tx = direct();
            String[] parts = path.split("\\.");
            ObjectNode parent = parts.length == 2 ? (ObjectNode) tx.get(parts[0]) : tx;
            parent.set(parts[parts.length - 1], JSON.readTree(value));
            rejected(verified(tx), EXPECTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mchid", "appid", "out_trade_no"})
    void validSignatureForAnotherMerchantAppOrOrderDoesNotMatch(String field) throws Exception {
        ObjectNode tx = direct();
        tx.put(field, "different-trusted-looking-value");
        rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND", "NOTPAY", "CLOSED", "REVOKED", "USERPAYING", "PAYERROR", "ACCEPT", "success"})
    void nonSuccessStateIsRejected(String state) throws Exception {
        ObjectNode tx = direct(); tx.put("trade_state", state);
        rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP", "NATIVE", "MWEB", "MICROPAY", "unknown"})
    void nonJsapiStructureIsNotGuessed(String type) throws Exception {
        ObjectNode tx = direct(); tx.put("trade_type", type);
        rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sp_mchid", "sp_appid", "sub_mchid", "sub_appid"})
    void partnerIdentityMustBePresentTypedAndExactlyExpected(String field) throws Exception {
        ObjectNode wrong = partner(); wrong.put(field, "different"); rejected(verified(wrong), expected(PARTNER, new BigDecimal("12.34"), null));
        ObjectNode missing = partner(); missing.remove(field); rejected(verified(missing), expected(PARTNER, new BigDecimal("12.34"), null));
        ObjectNode nullValue = partner(); nullValue.putNull(field); rejected(verified(nullValue), expected(PARTNER, new BigDecimal("12.34"), null));
        ObjectNode number = partner(); number.put(field, 123); rejected(verified(number), expected(PARTNER, new BigDecimal("12.34"), null));
    }

    @Test
    void mixedAndCrossModeIdentitiesAreRejectedRatherThanInferred() throws Exception {
        rejected(verified(partner()), EXPECTED);
        rejected(verified(direct()), expected(PARTNER, new BigDecimal("12.34"), null));
        ObjectNode mixedDirect = direct(); mixedDirect.putNull("sp_mchid"); rejected(verified(mixedDirect), EXPECTED);
        ObjectNode mixedPartner = partner(); mixedPartner.putNull("mchid"); rejected(verified(mixedPartner), expected(PARTNER, new BigDecimal("12.34"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1234\"", "true", "{}", "[]", "1234.0", "1.234e3", "1234.1", "0", "-1234", "1235", "2147483648", "999999999999999999999999999999999"})
    void totalMustBeExactPositiveIntCentsAndMatchExpected(String value) throws Exception {
        ObjectNode tx = direct(); ((ObjectNode) tx.get("amount")).set("total", JSON.readTree(value));
        rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "cny", "CNY ", "", "EUR"})
    void foreignOrMalformedCurrencyIsRejected(String currency) throws Exception {
        ObjectNode tx = direct(); ((ObjectNode) tx.get("amount")).put("currency", currency);
        rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1200\"", "-1", "1235", "1.5", "2147483648"})
    void presentPayerTotalMustAlsoBeValidButDoesNotReplaceGrossTotal(String value) throws Exception {
        ObjectNode tx = direct(); ((ObjectNode) tx.get("amount")).set("payer_total", JSON.readTree(value));
        rejected(verified(tx), EXPECTED);
    }

    @Test
    void optionalPayerFieldsMayBeAbsentAndZeroPayerTotalMayReflectPromotion() throws Exception {
        ObjectNode tx = direct(); ((ObjectNode) tx.get("amount")).remove("payer_total"); ((ObjectNode) tx.get("amount")).remove("payer_currency");
        var absent = MATCHER.match(verified(tx), EXPECTED);
        assertEquals(1234, absent.totalCents());
        assertNull(absent.payerTotalCents());
        ((ObjectNode) tx.get("amount")).put("payer_total", 0);
        var promoted = MATCHER.match(verified(tx), EXPECTED);
        assertEquals(1234, promoted.totalCents());
        assertEquals(0, promoted.payerTotalCents());
        ((ObjectNode) tx.get("amount")).put("payer_currency", "USD"); rejected(verified(tx), EXPECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-12.34", "12.341", "21474836.48", "999999999999999999999999999999", "12.35"})
    void invalidFractionalOverflowingOrDifferentServerAmountDoesNotRound(String yuan) throws Exception {
        rejected(verified(direct()), expected(DIRECT, new BigDecimal(yuan), null));
    }

    @Test
    void exactYuanConversionAcceptsEquivalentScaleAndMaximumSupportedCents() throws Exception {
        assertEquals(1234, MATCHER.match(verified(direct()), expected(DIRECT, new BigDecimal("12.3400"), null)).totalCents());
        ObjectNode tx = direct(); ((ObjectNode) tx.get("amount")).put("total", Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, MATCHER.match(verified(tx), expected(DIRECT, new BigDecimal("21474836.47"), null)).totalCents());
    }

    @Test
    void serverKnownTransactionIdMustMatchAndFirstUnknownIdIsReturnedForFuturePersistence() throws Exception {
        assertEquals(TX, MATCHER.match(verified(direct()), expected(DIRECT, new BigDecimal("12.34"), TX)).transactionId());
        rejected(verified(direct()), expected(DIRECT, new BigDecimal("12.34"), "another-transaction"));
        ObjectNode tx = direct(); tx.put("transaction_id", ""); rejected(verified(tx), EXPECTED);
        tx.put("transaction_id", "transaction\nvalue"); rejected(verified(tx), EXPECTED);
        tx.put("transaction_id", "x".repeat(33)); rejected(verified(tx), EXPECTED);
    }

    @Test
    void localPaymentAndOrderIdsComeOnlyFromServerSnapshotIncludingOrderlessPurchase() throws Exception {
        ObjectNode tx = direct(); tx.put("payment_record_id", 9999); tx.put("order_id", 9999);
        var result = MATCHER.match(verified(tx), EXPECTED);
        assertEquals(101, result.expected().paymentRecordId()); assertEquals(201L, result.expected().orderId());
        var orderless = new WechatPaySuccessMatcher.ExpectedPayment(101, null, PayMethod.WECHAT, DIRECT, "pay-A", new BigDecimal("12.34"), "CNY", null);
        assertNull(MATCHER.match(verified(tx), orderless).expected().orderId());
    }

    @Test
    void invalidServerSnapshotIsNotFilledFromTheCallback() throws Exception {
        var verified = verified(direct());
        rejected(verified, null); rejected(null, EXPECTED);
        rejected(verified, expected(null, new BigDecimal("12.34"), null));
        rejected(verified, expected(new WechatPaySuccessMatcher.DirectJsapi(null, "wx-app-A"), new BigDecimal("12.34"), null));
        rejected(verified, expected(new WechatPaySuccessMatcher.PartnerJsapiWithSubApp("provider-A", "wx-provider-A", "sub-merchant-A", null), new BigDecimal("12.34"), null));
        rejected(verified, expected(DIRECT, null, null));
        for (int method : new int[]{2, 3, 0}) rejected(verified, new WechatPaySuccessMatcher.ExpectedPayment(101, 201L, method, DIRECT, "pay-A", new BigDecimal("12.34"), "CNY", null));
        for (long id : new long[]{0, -1}) rejected(verified, new WechatPaySuccessMatcher.ExpectedPayment(id, 201L, PayMethod.WECHAT, DIRECT, "pay-A", new BigDecimal("12.34"), "CNY", null));
        rejected(verified, new WechatPaySuccessMatcher.ExpectedPayment(101, -1L, PayMethod.WECHAT, DIRECT, "pay-A", new BigDecimal("12.34"), "CNY", null));
        rejected(verified, new WechatPaySuccessMatcher.ExpectedPayment(101, 201L, PayMethod.WECHAT, DIRECT, null, new BigDecimal("12.34"), "CNY", null));
        rejected(verified, new WechatPaySuccessMatcher.ExpectedPayment(101, 201L, PayMethod.WECHAT, DIRECT, "pay-A", new BigDecimal("12.34"), "USD", null));
        rejected(verified, expected(DIRECT, new BigDecimal("12.34"), " "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_type", "resource_type", "id", "original_type"})
    void envelopeCriticalFieldsAreRequiredAndTyped(String field) throws Exception {
        ObjectNode missing = envelope(direct());
        ObjectNode missingParent = field.equals("original_type") ? (ObjectNode) missing.get("resource") : missing;
        missingParent.remove(field);
        rejected(verify(missing.toString()), EXPECTED);
        for (String invalid : new String[]{"null", "123", "true", "\"wrong-value\""}) {
            ObjectNode event = envelope(direct()); ObjectNode target = field.equals("original_type") ? (ObjectNode) event.get("resource") : event;
            target.set(field, JSON.readTree(invalid));
            if (field.equals("id") && invalid.equals("\"wrong-value\"")) continue;
            rejected(verify(event.toString()), EXPECTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_type", "resource_type", "id", "original_type"})
    void sdkAlreadyRejectsArrayEnvelopeFieldsBeforeMatching(String field) throws Exception {
        ObjectNode event = envelope(direct());
        ObjectNode target = field.equals("original_type") ? (ObjectNode) event.get("resource") : event;
        target.set(field, JSON.createArrayNode());
        assertThrows(WechatPayNotificationAdapter.RejectedNotificationException.class, () -> verify(event.toString()));
    }

    @Test
    void signedRefundEventAndDuplicatedEnvelopeEventTypeAreRejected() throws Exception {
        ObjectNode event = envelope(direct()); event.put("event_type", "REFUND.SUCCESS"); rejected(verify(event.toString()), EXPECTED);
        String duplicated = "{\"event_type\":\"REFUND.SUCCESS\"," + envelope(direct()).toString().substring(1);
        rejected(verify(duplicated), EXPECTED);
    }

    @Test
    void matchedResultHasNoPublicConstructionOrMutableState() {
        var type = WechatPaySuccessMatcher.MatchedPayment.class;
        assertEquals(0, type.getConstructors().length);
        assertTrue(Modifier.isFinal(type.getModifiers()));
        for (var field : type.getDeclaredFields()) assertTrue(Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
    }

    private static WechatPaySuccessMatcher.ExpectedPayment expected(WechatPaySuccessMatcher.MerchantIdentity merchant, BigDecimal amount, String knownTransaction) {
        return new WechatPaySuccessMatcher.ExpectedPayment(101, 201L, PayMethod.WECHAT, merchant, "pay-A", amount, "CNY", knownTransaction);
    }

    private static void rejected(WechatPayNotificationAdapter.VerifiedNotification verified, WechatPaySuccessMatcher.ExpectedPayment expected) {
        var failure = assertThrows(WechatPaySuccessMatcher.RejectedMatchException.class, () -> MATCHER.match(verified, expected));
        assertNull(failure.getCause()); assertEquals("微信支付回调与预期支付不匹配", failure.getMessage());
    }

    private static ObjectNode direct() {
        ObjectNode tx = JSON.createObjectNode().put("mchid", "merchant-A").put("appid", "wx-app-A").put("out_trade_no", "pay-A")
                .put("transaction_id", TX).put("trade_state", "SUCCESS").put("trade_type", "JSAPI");
        tx.set("amount", JSON.createObjectNode().put("total", 1234).put("currency", "CNY").put("payer_total", 1200).put("payer_currency", "CNY"));
        return tx;
    }

    private static ObjectNode partner() {
        ObjectNode tx = direct(); tx.remove("mchid"); tx.remove("appid");
        tx.put("sp_mchid", "provider-A").put("sp_appid", "wx-provider-A").put("sub_mchid", "sub-merchant-A").put("sub_appid", "wx-sub-A");
        return tx;
    }

    private static WechatPayNotificationAdapter.VerifiedNotification verified(ObjectNode tx) throws Exception { return verify(envelope(tx).toString()); }

    private static ObjectNode envelope(ObjectNode tx) throws Exception {
        byte[] nonceBytes = new byte[6]; new SecureRandom().nextBytes(nonceBytes); String nonce = HexFormat.of().formatHex(nonceBytes);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        ObjectNode resource = JSON.createObjectNode().put("original_type", "transaction").put("algorithm", "AEAD_AES_256_GCM").put("nonce", nonce)
                .put("associated_data", "transaction").put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(tx.toString().getBytes(StandardCharsets.UTF_8))));
        ObjectNode event = JSON.createObjectNode().put("id", "event-fixture").put("event_type", "TRANSACTION.SUCCESS").put("resource_type", "encrypt-resource");
        event.set("resource", resource); return event;
    }

    private static WechatPayNotificationAdapter.VerifiedNotification verify(String rawBody) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(keyPair.getPrivate());
        signer.update((TIMESTAMP + "\n" + HEADER_NONCE + "\n" + rawBody + "\n").getBytes(StandardCharsets.UTF_8));
        var headers = new WechatPayNotificationAdapter.Headers(SERIAL, TIMESTAMP, HEADER_NONCE, Base64.getEncoder().encodeToString(signer.sign()), null);
        return adapter.parseUtf8(headers, rawBody.getBytes(StandardCharsets.UTF_8));
    }
}
