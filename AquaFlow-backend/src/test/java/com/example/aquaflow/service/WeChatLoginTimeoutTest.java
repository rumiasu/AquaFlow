package com.example.aquaflow.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real configured factory and stalled loopback responses only; never calls WeChat or reads real credentials. */
class WeChatLoginTimeoutTest {
    private static final String SECRET = "synthetic-timeout-secret";
    private static final String CODE = "synthetic-timeout-code";

    private AnnotationConfigApplicationContext context(int connect, int read) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
                "wechat.miniapp.connect-timeout-ms", connect,
                "wechat.miniapp.read-timeout-ms", read,
                "wechat.miniapp.appid", "synthetic-customer", "wechat.miniapp.secret", SECRET,
                "wechat.miniapp.staff-appid", "synthetic-staff", "wechat.miniapp.staff-secret", SECRET)));
        context.register(WeChatLoginService.class);
        return context;
    }

    private RestTemplate transport(WeChatLoginService service) {
        return (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
    }

    private void assertTimeouts(WeChatLoginService service, int connect, int read) throws Exception {
        var factory = assertInstanceOf(SimpleClientHttpRequestFactory.class, transport(service).getRequestFactory());
        var connection = new HttpURLConnection(new URL("http://127.0.0.1/synthetic")) {
            public void connect() { fail("no network is used in the configuration assertion"); }
            public void disconnect() {}
            public boolean usingProxy() { return false; }
        };
        ReflectionTestUtils.invokeMethod(factory, "prepareConnection", connection, "GET");
        assertEquals(connect, connection.getConnectTimeout());
        assertEquals(read, connection.getReadTimeout());
    }

    @Test void defaultFactoryHasFiniteConnectionAndReadTimeouts() throws Exception {
        assertTimeouts(new WeChatLoginService(), 3000, 5000);
    }

    @Test void springConfigurationReachesTheActualConnectionFactory() throws Exception {
        try (var context = context(170, 230)) {
            context.refresh(); assertTimeouts(context.getBean(WeChatLoginService.class), 170, 230);
        }
    }

    @ParameterizedTest @CsvSource({"0, 100", "-1, 100", "100, 0", "100, -1"})
    void nonPositiveTimeoutCannotSilentlyEnableAnUnboundedWait(int connect, int read) {
        try (var context = context(connect, read)) {
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
        }
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void normalCodeExchangeTimesOutWithAFixedBusinessErrorAndNoCredentialLogs(WeChatApp app) throws Exception {
        stalledExchange(app, false);
    }

    @ParameterizedTest @EnumSource(WeChatApp.class)
    void invalidCodeDiagnosticUsesTheSameReadTimeoutAndPreservesTheOriginalRejection(WeChatApp app) throws Exception {
        stalledExchange(app, true);
    }

    private void stalledExchange(WeChatApp app, boolean diagnostic) throws Exception {
        try (var context = context(90, 120)) {
            context.refresh(); var service = context.getBean(WeChatLoginService.class);
            assertTimeouts(service, 90, 120);
            var rest = transport(service); var factory = rest.getRequestFactory();
            var requests = new AtomicInteger(); var release = new CountDownLatch(1);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
            server.createContext("/synthetic", exchange -> {
                int call = requests.incrementAndGet();
                if (diagnostic && call == 1) {
                    byte[] body = "{\"errcode\":40029,\"errmsg\":\"synthetic invalid code\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
                } else {
                    exchange.sendResponseHeaders(200, 100);
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { exchange.close(); }
                }
            });
            server.start();
            // Only rewrite the destination; retain the real factory's finite timeout behavior on both actual calls.
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic");
            rest.setRequestFactory((uri, method) -> factory.createRequest(local, method));
            var logger = (Logger) LoggerFactory.getLogger(WeChatLoginService.class);
            var events = new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events);
            try {
                BusinessException error = assertThrows(BusinessException.class, () -> service.code2Session(app, CODE));
                assertEquals(diagnostic ? "微信登录失败：凭证已失效，请重开小程序再试" : "微信登录服务暂时不可用，请稍后重试", error.getMessage());
                assertNull(error.getCause()); assertEquals(diagnostic ? 2 : 1, requests.get());
                assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("异常类型=")));
                for (var event : events.list) {
                    String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
                    assertFalse(text.contains(SECRET)); assertFalse(text.contains(CODE)); assertFalse(text.contains("https://api.weixin.qq.com"));
                }
            } finally {
                logger.detachAppender(events); events.stop(); release.countDown(); server.stop(0); executor.shutdownNow();
            }
        }
    }
}
