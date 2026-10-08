package com.example.aquaflow.payment.wechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.StringWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Independent JCA fixtures keep private keys in memory and exercise the SDK's real verifier. */
class WechatPayNotificationAdapterTest {
    private static final String KEY_ID = "PUB_KEY_ID_offline_fixture";
    private static final String TIMESTAMP = "1791000000";
    private static final Instant NOW = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP));
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String NONCE = "ephemeral-header-nonce";
    private static final String RESOURCE_NONCE = "testnonce123";
    private static final String ASSOCIATED_DATA = "transaction";
    private static final String PAYLOAD = "{\"transaction_id\":\"offline-transaction\",\"amount\":{\"total\":123},\"note\":\"临时测试\"}";
    private static KeyPair platformKey;
    private static String apiV3Key;
    private static WechatPayNotificationAdapter adapter;

    @BeforeAll
    static void temporaryKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        platformKey = generator.generateKeyPair();
        apiV3Key = randomKey();
        adapter = new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, apiV3Key, FIXED_CLOCK);
    }

    @Test
    void validNotificationPreservesExactRawBodyAndDecryptsWithoutBusinessAssumptions() throws Exception {
        String body = "  \n" + body(PAYLOAD, apiV3Key) + "\n  ";
        var result = adapter.parse(headers(body), body);
        assertEquals(body, result.rawBody());
        assertEquals(JsonParser.parseString(PAYLOAD), JsonParser.parseString(result.resourceJson()));
        assertFalse(result.toString().contains(body));
        assertFalse(result.toString().contains("offline-transaction"));
    }

    @Test
    void sameInputHasStableResult() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        var headers = headers(body);
        var first = adapter.parse(headers, body);
        var second = adapter.parse(headers, body);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, null);
        assertNotEquals(first, new Object());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"mchid\":\"wrong\",\"mchid\":\"expected\"}",
            "{\"mchid\":\"expected\",\"mchid\":\"wrong\"}",
            "{\"mchid\":\"same\",\"mchid\":\"same\"}",
            "{\"amount\":{\"total\":1,\"total\":123}}",
            "{\"payer\":{\"openid\":\"first\",\"openid\":\"second\"}}",
            "{\"items\":[{\"id\":1,\"id\":2}]}",
            "{\"mchid\":\"first\",\"mch\\u0069d\":\"second\"}",
            "{\"transaction_id\":\"first\",\"transaction_id\":\"second\"}",
            "{\"amount\":{\"total\":123},\"amount\":{\"total\":1}}"
    })
    void signedEncryptedResourceWithDuplicateKeysIsRejectedBeforeGson(String plaintext) throws Exception {
        String body = body(plaintext, apiV3Key);
        // Independent JCA encryption and signing reach the SDK's actual decrypted-resource path.
        rejected(headers(body), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{} {}", "{mchid:'permissive'}", "{\"value\":1}//comment", "null", "[]"})
    void decryptedResourceMustBeOneStrictJsonObject(String plaintext) throws Exception {
        String body = body(plaintext, apiV3Key);
        rejected(headers(body), body);
    }

    @Test
    void repeatedNamesInSeparateObjectsAndQuotedTextRemainValid() throws Exception {
        String plaintext = "{\"items\":[{\"id\":1},{\"id\":2}],\"note\":\"mchid,mchid\",\"amount\":{\"total\":123}}";
        String body = body(plaintext, apiV3Key);
        assertEquals(JsonParser.parseString(plaintext), JsonParser.parseString(adapter.parse(headers(body), body).resourceJson()));
    }

    @Test
    void tamperedRawBodyIsRejectedEvenIfJsonMeaningIsUnchanged() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(headers(body), body + " ");
    }

    @Test
    void incorrectSignatureIsRejected() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        byte[] signature = Base64.getDecoder().decode(headers(body).signature());
        signature[0] ^= 1;
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, TIMESTAMP, NONCE,
                Base64.getEncoder().encodeToString(signature), null), body);
    }

    @Test
    void unknownPublicKeyIdIsRejected() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(new WechatPayNotificationAdapter.Headers("unknown", TIMESTAMP, NONCE,
                headers(body).signature(), null), body);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void eachRequiredHeaderIsRejectedWhenMissingOrBlank(int missing) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        String[] values = {KEY_ID, TIMESTAMP, NONCE, headers(body).signature()};
        for (String invalid : new String[]{null, "", " ", "bad\nheader"}) {
            String previous = values[missing];
            values[missing] = invalid;
            rejected(new WechatPayNotificationAdapter.Headers(values[0], values[1], values[2], values[3], null), body);
            values[missing] = previous;
        }
    }

    @Test
    void missingHeadersBodyAndMalformedTimestampAreRejected() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(null, body);
        rejected(headers(body), null);
        rejected(headers(body), " ");
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, "not-seconds", NONCE,
                headers(body).signature(), null), body);
    }

    @Test
    void unsupportedSignatureTypeIsRejected() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, TIMESTAMP, NONCE,
                headers(body).signature(), "unknown-signature-algorithm"), body);
    }

    @Test
    void explicitlySupportedSignatureTypeIsAccepted() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        var headers = new WechatPayNotificationAdapter.Headers(KEY_ID, TIMESTAMP, NONCE,
                headers(body).signature(), "WECHATPAY2-SHA256-RSA2048");
        assertEquals(JsonParser.parseString(PAYLOAD), JsonParser.parseString(adapter.parse(headers, body).resourceJson()));
    }

    @Test
    void signedBodyEncryptedWithAnotherApiV3KeyIsRejected() throws Exception {
        String body = body(PAYLOAD, randomKey());
        rejected(headers(body), body);
    }

    @Test
    void signedCorruptedCiphertextIsRejected() throws Exception {
        JsonObject envelope = JsonParser.parseString(body(PAYLOAD, apiV3Key)).getAsJsonObject();
        JsonObject resource = envelope.getAsJsonObject("resource");
        byte[] ciphertext = Base64.getDecoder().decode(resource.get("ciphertext").getAsString());
        ciphertext[ciphertext.length - 1] ^= 1;
        resource.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        String body = envelope.toString();
        rejected(headers(body), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{", "{\"resource\":{\"algorithm\":\"unsupported\",\"ciphertext\":\"invalid\",\"nonce\":\"testnonce123\"}}"})
    void validSignatureDoesNotMakeMalformedEnvelopeAcceptable(String body) throws Exception {
        rejected(headers(body), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[1,2]", "{"})
    void decryptedResourceMustBeAJsonObject(String plaintext) throws Exception {
        String body = body(plaintext, apiV3Key);
        rejected(headers(body), body);
    }

    @Test
    void signedResourceRetainsLargeNumericLiteralWithoutDoubleRounding() throws Exception {
        String plaintext = "{\"opaque_number\":123456789012345678901234567890}";
        String body = body(plaintext, apiV3Key);
        assertEquals(plaintext, adapter.parse(headers(body), body).resourceJson());
    }

    @Test
    void malformedBase64SignatureIsRejectedWithoutLeakingSdkException() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, TIMESTAMP, NONCE, "%%%", null), body);
    }

    @Test
    void ordinaryCallerCannotForgeVerifiedNotification() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The boundary regression requires the configured Java 17 JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var source = new SimpleJavaFileObject(URI.create("string:///UntrustedNotificationCaller.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "package external.consumer; public class UntrustedNotificationCaller { "
                        + "Object forge() { return new com.example.aquaflow.payment.wechat."
                        + "WechatPayNotificationAdapter.VerifiedNotification(\"unsigned\", \"{}\"); } }";
            }
        };
        String classPath = Path.of(WechatPayNotificationAdapter.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            // Keep the access-rejection assertion; compilation output needs no Windows temporary-directory lifecycle.
            var output = new ForwardingJavaFileManager<StandardJavaFileManager>(files) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                           JavaFileObject.Kind kind, FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() { return new ByteArrayOutputStream(); }
                    };
                }
            };
            boolean compiled = compiler.getTask(new StringWriter(), output, diagnostics,
                    List.of("-classpath", classPath, "-proc:none"),
                    null, List.of(source)).call();
            assertFalse(compiled, "An ordinary caller must not construct an unsigned verification result");
            assertTrue(diagnostics.getDiagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.getCode().equals("compiler.err.report.access")),
                    () -> "Expected constructor access rejection: " + diagnostics.getDiagnostics());
        }
    }

    @Test
    void ancientCorrectlySignedNotificationIsRejected() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(headers(body, "0", NONCE), body);
    }

    @Test
    void validUtf8BytesRetainWhitespaceUnicodeAndReplacementCharacterExactly() throws Exception {
        String body = " \r\n{\"summary\":\"水💧 café e\u0301 \ufffd\","
                + body(PAYLOAD, apiV3Key).substring(1) + "\r\n ";
        byte[] wire = body.getBytes(StandardCharsets.UTF_8);
        var result = adapter.parseUtf8(headers(body), wire);
        assertEquals(body, result.rawBody());
        assertArrayEquals(wire, result.rawBody().getBytes(StandardCharsets.UTF_8));
        assertEquals(adapter.parse(headers(body), body), result);
        wire[0] = 'x';
        assertEquals(body, result.rawBody(), "The immutable result does not retain a mutable request byte array");
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "c0af", "e282", "eda080", "f4908080", "e228a1", "f09f8c", "ff"})
    void invalidUtf8CannotBeReplacedBeforeSignatureVerification(String hex) throws Exception {
        byte[] prefix = "{\"summary\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = HexFormat.of().parseHex(hex);
        byte[] suffix = ("\"," + body(PAYLOAD, apiV3Key).substring(1)).getBytes(StandardCharsets.UTF_8);
        byte[] wire = new byte[prefix.length + malformed.length + suffix.length];
        System.arraycopy(prefix, 0, wire, 0, prefix.length);
        System.arraycopy(malformed, 0, wire, prefix.length, malformed.length);
        System.arraycopy(suffix, 0, wire, prefix.length + malformed.length, suffix.length);
        String replacedBody = new String(wire, StandardCharsets.UTF_8);
        var signedReplacement = headers(replacedBody);
        assertNotNull(adapter.parse(signedReplacement, replacedBody), "A permissive decoder would incorrectly accept this signed replacement text");
        rejected(() -> adapter.parseUtf8(signedReplacement, wire));
    }

    @Test
    void nullEmptyAndBlankByteBodiesAreRejected() throws Exception {
        var headers = headers(body(PAYLOAD, apiV3Key));
        rejected(() -> adapter.parseUtf8(headers, null));
        rejected(() -> adapter.parseUtf8(headers, new byte[0]));
        rejected(() -> adapter.parseUtf8(headers, " \r\n\t".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, -299, 0, 299, 300})
    void currentAndExactClockWindowBoundariesAreAccepted(long offset) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        var headers = headers(body, Long.toString(NOW.getEpochSecond() + offset), NONCE);
        assertEquals(body, adapter.parse(headers, body).rawBody());
        assertEquals(body, adapter.parseUtf8(headers, body.getBytes(StandardCharsets.UTF_8)).rawBody());
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, -3600, 301, 3600})
    void correctlySignedOldAndFutureRequestsOutsideWindowAreRejected(long offset) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(headers(body, Long.toString(NOW.getEpochSecond() + offset), NONCE), body);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1})
    void oneNanosecondBeyondClockBoundaryIsRejected(long direction) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        Clock clock = Clock.fixed(NOW.plusNanos(-direction), ZoneOffset.UTC);
        var shifted = new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, apiV3Key, clock);
        var headers = headers(body, Long.toString(NOW.getEpochSecond() + direction * 300), NONCE);
        rejected(() -> shifted.parse(headers, body));
        rejected(() -> shifted.parseUtf8(headers, body.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "+1791000000", "1.5", "1e9", " 1791000000", "1791000000 ",
            "9223372036854775808", "9223372036854775807", "１７９１００００００"})
    void malformedOrOverflowingTimestampIsRejected(String timestamp) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(headers(body, timestamp, NONCE), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"with space", "nonce\tvalue", "nonce\u0000value", "nonce\u007fvalue", "nonce-水"})
    void signedNonTokenNonceIsRejectedWithoutNormalizingIt(String nonce) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(headers(body, TIMESTAMP, nonce), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "value\r"})
    void presentButMalformedSignatureTypeIsRejected(String signatureType) throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, TIMESTAMP, NONCE,
                headers(body).signature(), signatureType), body);
    }

    @Test
    void changingSignedTimestampWithinWindowStillFailsSdkSignatureCheck() throws Exception {
        String body = body(PAYLOAD, apiV3Key);
        rejected(new WechatPayNotificationAdapter.Headers(KEY_ID, Long.toString(NOW.getEpochSecond() + 1), NONCE,
                headers(body).signature(), null), body);
    }

    @Test
    void clockIsConsultedForEveryRequestAndDoesNotProvideDurableIdempotency() throws Exception {
        var now = new AtomicReference<>(NOW);
        Clock advancingClock = new Clock() {
            @Override
            public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(ZoneId zone) { return this; }
            @Override
            public Instant instant() { return now.get(); }
        };
        var dynamic = new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, apiV3Key, advancingClock);
        String body = body(PAYLOAD, apiV3Key);
        var headers = headers(body);
        assertEquals(dynamic.parse(headers, body), dynamic.parse(headers, body));
        now.set(NOW.plusSeconds(301));
        rejected(() -> dynamic.parse(headers, body));
    }

    @Test
    void payloadCreateTimeDoesNotReplaceSignedRequestTimestamp() throws Exception {
        JsonObject envelope = JsonParser.parseString(body(PAYLOAD, apiV3Key)).getAsJsonObject();
        envelope.addProperty("create_time", "1970-01-01T00:00:00Z");
        String body = envelope.toString();
        assertEquals(body, adapter.parse(headers(body), body).rawBody());
        envelope.addProperty("create_time", NOW.toString());
        String changed = envelope.toString();
        rejected(headers(changed, "0", NONCE), changed);
    }

    @Test
    void defaultConstructorUsesCurrentSystemClock() throws Exception {
        var current = new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, apiV3Key);
        String body = body(PAYLOAD, apiV3Key);
        var headers = headers(body, Long.toString(Instant.now().getEpochSecond()), NONCE);
        assertEquals(body, current.parseUtf8(headers, body.getBytes(StandardCharsets.UTF_8)).rawBody());
    }

    @Test
    void invalidConfigurationFailsBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, "short"));
        assertThrows(IllegalArgumentException.class, () -> new WechatPayNotificationAdapter(platformKey.getPublic(), " ", apiV3Key));
        assertThrows(IllegalArgumentException.class, () -> new WechatPayNotificationAdapter(null, KEY_ID, apiV3Key));
        assertThrows(IllegalArgumentException.class, () -> new WechatPayNotificationAdapter(platformKey.getPublic(), KEY_ID, apiV3Key, null));
    }

    private static void rejected(WechatPayNotificationAdapter.Headers headers, String body) {
        rejected(() -> adapter.parse(headers, body));
        rejected(() -> adapter.parseUtf8(headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejected(Runnable parsing) {
        var failure = assertThrows(WechatPayNotificationAdapter.RejectedNotificationException.class,
                parsing::run);
        assertNull(failure.getCause(), "SDK errors may carry the raw callback or decrypted customer data");
        assertEquals("微信支付回调验证或解密失败", failure.getMessage());
    }

    private static String randomKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return HexFormat.of().formatHex(key);
    }

    private static WechatPayNotificationAdapter.Headers headers(String body) throws Exception {
        return headers(body, TIMESTAMP, NONCE);
    }

    private static WechatPayNotificationAdapter.Headers headers(String body, String timestamp, String nonce) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(platformKey.getPrivate());
        signer.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        return new WechatPayNotificationAdapter.Headers(KEY_ID, timestamp, nonce,
                Base64.getEncoder().encodeToString(signer.sign()), null);
    }

    private static String body(String plaintext, String key) throws Exception {
        byte[] nonceBytes = new byte[6];
        new SecureRandom().nextBytes(nonceBytes);
        String resourceNonce = HexFormat.of().formatHex(nonceBytes);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, resourceNonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(ASSOCIATED_DATA.getBytes(StandardCharsets.UTF_8));
        JsonObject resource = new JsonObject();
        resource.addProperty("original_type", "transaction");
        resource.addProperty("algorithm", "AEAD_AES_256_GCM");
        resource.addProperty("nonce", resourceNonce);
        resource.addProperty("associated_data", ASSOCIATED_DATA);
        resource.addProperty("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))));
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", "offline-notification");
        envelope.addProperty("event_type", "TRANSACTION.SUCCESS");
        envelope.addProperty("resource_type", "encrypt-resource");
        envelope.add("resource", resource);
        return envelope.toString();
    }
}
