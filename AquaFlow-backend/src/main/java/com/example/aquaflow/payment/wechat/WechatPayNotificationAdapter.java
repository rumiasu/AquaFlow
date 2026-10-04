package com.example.aquaflow.payment.wechat;

import com.google.gson.JsonObject;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.core.notification.RequestParam;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 离线回调密码学适配器，未注册 Spring bean 或 HTTP 入口。
 * 固定公钥配置不会下载证书；签名和资源解密均交给微信支付官方 SDK。
 * 验证结果不表示订单可入账：接线仍须商户/订单/金额核对和持久幂等门槛。
 * TODO(待拍板)：直连/服务商及站级收退款主体绑定见 docs/design/16 §9.3 C-01；本类不选择商户模式。
 */
public final class WechatPayNotificationAdapter {
    // SDK 0.2.17 的通知解析不检查时间。前后各 5 分钟（含边界）是工程假设，非官方强制值。
    // 请求时间戳以服务器 Clock 为准，不用正文 create_time；该窗口不替代持久幂等。
    private static final Duration MAX_REQUEST_CLOCK_SKEW = Duration.ofMinutes(5);
    private final NotificationParser parser;
    private final String publicKeyId;
    private final Clock clock;

    public WechatPayNotificationAdapter(PublicKey publicKey, String publicKeyId, String apiV3Key) {
        this(publicKey, publicKeyId, apiV3Key, Clock.systemUTC());
    }

    public WechatPayNotificationAdapter(PublicKey publicKey, String publicKeyId, String apiV3Key, Clock clock) {
        if (!(publicKey instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() < 2048
                || invalidHeader(publicKeyId) || apiV3Key == null || clock == null
                || apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new IllegalArgumentException("微信支付回调公钥或 API v3 配置无效");
        }
        this.publicKeyId = publicKeyId;
        this.clock = clock;
        this.parser = new NotificationParser(new RSAPublicKeyNotificationConfig.Builder()
                .publicKey(publicKey).publicKeyId(publicKeyId).apiV3Key(apiV3Key).build());
    }

    /** 原始请求字节须严格解码；不能默认替换非法 UTF-8，也不能 trim 或重序列化。 */
    public VerifiedNotification parseUtf8(Headers headers, byte[] rawBody) {
        if (rawBody == null) {
            throw new RejectedNotificationException();
        }
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBody)).toString();
            return parse(headers, body);
        } catch (CharacterCodingException failure) {
            throw new RejectedNotificationException();
        }
    }

    /** 仅接收已正确解码的原文 String；未来 HTTP 入口应传原始 byte[] 给 parseUtf8。 */
    public VerifiedNotification parse(Headers headers, String rawBody) {
        if (headers == null || rawBody == null || rawBody.isBlank()
                || invalidHeader(headers.serialNumber()) || invalidHeader(headers.timestamp())
                || invalidHeader(headers.nonce()) || invalidHeader(headers.signature())
                || !publicKeyId.equals(headers.serialNumber())
                || (headers.signatureType() != null && invalidHeader(headers.signatureType()))) {
            throw new RejectedNotificationException();
        }
        try {
            if (!headers.timestamp().matches("[0-9]+")) {
                throw new RejectedNotificationException();
            }
            Instant requestTime = Instant.ofEpochSecond(Long.parseLong(headers.timestamp()));
            if (Duration.between(clock.instant(), requestTime).abs().compareTo(MAX_REQUEST_CLOCK_SKEW) > 0) {
                throw new RejectedNotificationException();
            }
            RequestParam request = new RequestParam.Builder()
                    .serialNumber(headers.serialNumber()).timestamp(headers.timestamp())
                    .nonce(headers.nonce()).signature(headers.signature())
                    .signType(headers.signatureType()).body(rawBody).build();
            JsonObject resource = parser.parse(request, JsonObject.class);
            if (resource == null) {
                throw new RejectedNotificationException();
            }
            // 保留 JSON 数字的字面值，避免通用 Map 解析将金额先转成 double。
            return new VerifiedNotification(rawBody, resource.toString());
        } catch (RuntimeException failure) {
            // SDK 异常可能含正文或解密数据；不附带 cause，不将其送入未来的业务日志。
            throw new RejectedNotificationException();
        }
    }

    private static boolean invalidHeader(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        // 这些签名相关头是可见 ASCII token；不裁剪空白或吞掉控制字符以免改写签名输入。
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x20 || character >= 0x7f) {
                return true;
            }
        }
        return false;
    }

    /** signatureType 省略时由 SDK 使用标准 RSA 签名类型；四个签名相关头必须存在且为单值 token；HTTP 接线还须拒绝重复头。 */
    public record Headers(String serialNumber, String timestamp, String nonce,
                          String signature, String signatureType) {
        @Override
        public String toString() {
            return "WechatPayNotificationHeaders[redacted]";
        }
    }

    /** 验证结果只能由适配器创建，避免普通调用方将未验签正文包装成可信结果。 */
    public static final class VerifiedNotification {
        private final String rawBody;
        private final String resourceJson;

        private VerifiedNotification(String rawBody, String resourceJson) {
            this.rawBody = rawBody;
            this.resourceJson = resourceJson;
        }

        public String rawBody() {
            return rawBody;
        }

        public String resourceJson() {
            return resourceJson;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof VerifiedNotification notification
                    && rawBody.equals(notification.rawBody)
                    && resourceJson.equals(notification.resourceJson);
        }

        @Override
        public int hashCode() {
            return 31 * rawBody.hashCode() + resourceJson.hashCode();
        }

        @Override
        public String toString() {
            return "VerifiedWechatPayNotification[redacted]";
        }
    }

    public static final class RejectedNotificationException extends RuntimeException {
        private RejectedNotificationException() {
            super("微信支付回调验证或解密失败");
        }
    }
}
