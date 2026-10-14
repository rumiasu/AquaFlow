package com.example.aquaflow.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Only synthetic provider responses and in-memory log events; no real network, logs or credentials. */
class WeChatLoginLoggingTest {
    private static final String OPENID = "synthetic-openid-private-tail";
    private static final String SESSION = "synthetic-session-private-tail";
    private WeChatLoginService service;
    private MockRestServiceServer server;
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach void setUp() {
        service = new WeChatLoginService();
        for (String field : new String[]{"customerAppid", "staffAppid"}) ReflectionTestUtils.setField(service, field, "synthetic-app");
        for (String field : new String[]{"customerSecret", "staffSecret"}) ReflectionTestUtils.setField(service, field, "synthetic-placeholder");
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(service, "restTemplate")).build();
        logger = (Logger) LoggerFactory.getLogger(WeChatLoginService.class);
        previousLevel = logger.getLevel(); logger.setLevel(Level.INFO);
        events = new ListAppender<>(); events.start(); logger.addAppender(events);
    }

    @AfterEach void tearDown() {
        logger.detachAppender(events); events.stop(); logger.setLevel(previousLevel);
    }

    private void respond(String response) {
        server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static final String SENSITIVE_URL = "https://api.weixin.qq.com/sns/jscode2session?secret=synthetic-private-secret&js_code=synthetic-private-code";

    private ILoggingEvent ioFailure(WeChatApp app, RuntimeException failure) {
        server.expect(anything()).andRespond(request -> { throw failure; });
        BusinessException error = assertThrows(BusinessException.class, () -> service.code2Session(app, "synthetic-private-code"));
        assertEquals("微信登录服务暂时不可用，请稍后重试", error.getMessage());
        assertNull(error.getCause());
        server.verify();
        ILoggingEvent event = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        String text = event.getFormattedMessage();
        assertAll(() -> assertNull(event.getThrowableProxy()),
                () -> assertFalse(text.contains(SENSITIVE_URL)),
                () -> assertFalse(text.contains("synthetic-private-secret")),
                () -> assertFalse(text.contains("synthetic-private-code")),
                () -> assertFalse(text.contains("https://api.weixin.qq.com")),
                () -> assertTrue(text.contains("阶段=CODE2SESSION_HTTP")),
                () -> assertTrue(text.matches("(?s).*耗时毫秒=\\d+.*")));
        return event;
    }

    private static final class GrowingCause extends IOException {
        private final AtomicInteger reads;
        GrowingCause(AtomicInteger reads) { super(SENSITIVE_URL); this.reads = reads; }
        @Override public synchronized Throwable getCause() { reads.incrementAndGet(); return new GrowingCause(reads); }
    }

    private static final class ThrowingCause extends IOException {
        ThrowingCause() { super(SENSITIVE_URL); }
        @Override public synchronized Throwable getCause() { throw new IllegalStateException(SENSITIVE_URL); }
    }

    private void assertNoSensitiveLogValues() {
        assertFalse(events.list.isEmpty(), "the actual response logging branch must execute");
        for (ILoggingEvent event : events.list) {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertAll(() -> assertFalse(text.contains(OPENID), "synthetic openid must not survive in any log event"),
                    () -> assertFalse(text.contains(SESSION), "synthetic session key must not survive in message or throwable"));
        }
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void whitespaceResponseLogsAreMaskedWithoutChangingTheLoginResult(WeChatApp app) {
        respond("{\n\t\"openid\" \t: \""+OPENID+"\",\n \"session_key\" : \""+SESSION+"\"\n}");
        var result = service.code2Session(app, "synthetic-code");
        assertEquals(OPENID, result.get("openid")); assertEquals(SESSION, result.get("session_key"));
        server.verify(); assertNoSensitiveLogValues();
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void malformedResponsesCannotLeakThroughEitherTheResponseOrParserThrowable(WeChatApp app) {
        respond("{\"openid\" : \""+OPENID+"\",\"session_key\" : \""+SESSION+"\",\"broken\":"+SESSION+"}");
        BusinessException error = assertThrows(BusinessException.class, () -> service.code2Session(app, "synthetic-code"));
        assertEquals("微信登录响应解析失败", error.getMessage()); assertNull(error.getCause());
        server.verify(); assertNoSensitiveLogValues();
        ILoggingEvent parseFailure = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertNull(parseFailure.getThrowableProxy(), "parser exceptions may embed raw provider values");
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void missingOpenidErrorLogsAlsoMaskTheProviderSessionKey(WeChatApp app) {
        respond("{\n\"session_key\"\t:\t\""+SESSION+"\"\n}");
        BusinessException error = assertThrows(BusinessException.class, () -> service.code2Session(app, "synthetic-code"));
        assertEquals("微信登录失败，请稍后再试", error.getMessage());
        server.verify(); assertNoSensitiveLogValues();
        assertTrue(events.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
    }
    @ParameterizedTest @EnumSource(WeChatApp.class)
    void dnsFailureLogsTypeWithoutTheSensitiveExceptionMessage(WeChatApp app) {
        var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL, new UnknownHostException(SENSITIVE_URL)));
        assertTrue(event.getFormattedMessage().contains("根因类型=java.net.UnknownHostException"));
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void nestedTlsFailureLogsOnlyTheDeepestType(WeChatApp app) {
        var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL, new IOException(SENSITIVE_URL, new SSLHandshakeException(SENSITIVE_URL))));
        assertTrue(event.getFormattedMessage().contains("根因类型=javax.net.ssl.SSLHandshakeException"));
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void readTimeoutKeepsTheOriginalBusinessErrorAndLogsItsRootType(WeChatApp app) {
        var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL, new SocketTimeoutException(SENSITIVE_URL)));
        assertTrue(event.getFormattedMessage().contains("根因类型=java.net.SocketTimeoutException"));
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void missingCauseLogsTheExceptionTypeWithoutInventingACause(WeChatApp app) {
        var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL));
        assertTrue(event.getFormattedMessage().contains("根因类型=org.springframework.web.client.ResourceAccessException"));
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void causeCyclesCannotHangTheOriginalFailurePath(WeChatApp app) {
        IOException first = new IOException(SENSITIVE_URL), second = new IOException(SENSITIVE_URL);
        first.initCause(second); second.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL, first));
            assertTrue(event.getFormattedMessage().contains("根因类型=java.io.IOException"));
        });
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void throwingCauseAccessorCannotReplaceTheOriginalBusinessFailure(WeChatApp app) {
        var event = ioFailure(app, new ResourceAccessException(SENSITIVE_URL, new ThrowingCause()));
        assertTrue(event.getFormattedMessage().contains("根因类型=" + ThrowingCause.class.getName()));
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void continuallyGrowingCauseChainIsBounded(WeChatApp app) {
        AtomicInteger reads = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ioFailure(app, new ResourceAccessException(SENSITIVE_URL, new GrowingCause(reads))));
        assertTrue(reads.get() <= 16, "cause traversal must have a fixed upper bound");
    }

}
