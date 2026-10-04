package com.example.aquaflow.interceptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** No application datasource: execute the real limiter and its Spring configuration boundary. */
class RateLimitInterceptorTest {
    private RateLimitInterceptor limiter(int limit, boolean enabled) {
        RateLimitInterceptor limiter = new RateLimitInterceptor();
        ReflectionTestUtils.setField(limiter, "enabled", enabled);
        ReflectionTestUtils.setField(limiter, "limitPerMinute", limit);
        return limiter;
    }

    private MockHttpServletRequest request(String remote, String mode, int attempt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        request.setRequestURI(attempt % 2 == 0 ? "/api/auth/login" : "/api/auth/refresh");
        String fake = "203.0.113." + (attempt + 1);
        switch (mode) {
            case "single" -> request.addHeader("X-Forwarded-For", fake);
            case "multi-hop" -> request.addHeader("X-Forwarded-For", fake + ", 192.0.2.8, 127.0.0.1");
            case "real-ip" -> request.addHeader("X-Real-IP", fake);
            case "both" -> {
                request.addHeader("X-Forwarded-For", fake + ", 192.0.2.8");
                request.addHeader("X-Real-IP", fake);
            }
            case "duplicate" -> {
                request.addHeader("X-Forwarded-For", fake);
                request.addHeader("X-Forwarded-For", "127.0.0.1");
            }
            case "empty-hop" -> request.addHeader("X-Forwarded-For", fake + ", , 127.0.0.1");
            case "ipv6" -> request.addHeader("X-Forwarded-For", "2001:db8::" + attempt + ", ::1");
            case "malformed" -> request.addHeader("X-Forwarded-For", "not-an-address-" + attempt);
            case "forwarded" -> request.addHeader("Forwarded", "for=" + fake + ";proto=https");
            default -> throw new IllegalArgumentException(mode);
        }
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "multi-hop", "real-ip", "both", "duplicate", "empty-hop", "ipv6", "malformed", "forwarded"})
    void changingForwardedHeadersCannotResetTheConnectionAddressQuota(String mode) throws Exception {
        RateLimitInterceptor limiter = limiter(3, true);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.preHandle(request("198.51.100.42", mode, i), new MockHttpServletResponse(), null));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("198.51.100.42", mode, 3), response, null));
        assertEquals(429, response.getStatus());
        assertEquals("{\"code\":1,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}", response.getContentAsString());
    }

    @Test
    void distinctConnectionAddressesHaveIndependentQuotasDespiteIdenticalHeaders() throws Exception {
        RateLimitInterceptor limiter = limiter(1, true);
        assertTrue(limiter.preHandle(request("198.51.100.1", "single", 0), new MockHttpServletResponse(), null));
        assertTrue(limiter.preHandle(request("198.51.100.2", "single", 0), new MockHttpServletResponse(), null));
        assertFalse(limiter.preHandle(request("198.51.100.1", "single", 0), new MockHttpServletResponse(), null));
    }

    @Test
    void missingConnectionAddressesShareOneConservativeQuota() throws Exception {
        RateLimitInterceptor limiter = limiter(1, true);
        assertTrue(limiter.preHandle(request(null, "single", 0), new MockHttpServletResponse(), null));
        assertFalse(limiter.preHandle(request(null, "single", 1), new MockHttpServletResponse(), null));
    }

    @Test
    void disabledLimiterStillAllowsRequests() throws Exception {
        RateLimitInterceptor limiter = limiter(1, false);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.preHandle(request("198.51.100.1", "single", i), new MockHttpServletResponse(), null));
        }
    }

    @Test
    void springDefaultAndExplicitNonePreserveConnectionAddressMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RateLimitInterceptor.class);
        runner.run(context -> assertNull(context.getStartupFailure()));
        runner.withPropertyValues("server.forward-headers-strategy=none")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote-ip-header", "protocol-header"})
    void explicitTomcatHeadersCannotEnableAddressRewritingWithNoneStrategy(String option) {
        String property = "server.tomcat.remoteip." + option;
        new ApplicationContextRunner().withUserConfiguration(RateLimitInterceptor.class)
                .withPropertyValues("server.forward-headers-strategy=none", property + "=X-Forwarded-For")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertTrue(cause.getMessage().contains(property));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"framework", "native", "unknown"})
    void springForwardedAddressRewritingIsRejectedBeforeServingRequests(String strategy) {
        new ApplicationContextRunner().withUserConfiguration(RateLimitInterceptor.class)
                .withPropertyValues("server.forward-headers-strategy=" + strategy)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertTrue(cause.getMessage().contains("server.forward-headers-strategy"));
                });
    }
}
