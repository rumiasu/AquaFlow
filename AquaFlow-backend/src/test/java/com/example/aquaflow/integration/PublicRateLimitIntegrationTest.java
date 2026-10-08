package com.example.aquaflow.integration;

import com.example.aquaflow.interceptor.RateLimitInterceptor;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Anonymous station discovery has one IP quota independent from authentication and liveness. */
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.auth-per-minute=3",
        "app.rate-limit.public-enabled=true", "app.rate-limit.public-per-minute=3"})
class PublicRateLimitIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private RateLimitInterceptor authLimit;

    @BeforeEach void clearCounters() {
        ((Map<?, ?>) ReflectionTestUtils.getField(authLimit, "windows")).clear();
        if (context.containsBean("publicRateLimitInterceptor"))
            ((Map<?, ?>) ReflectionTestUtils.getField(context.getBean("publicRateLimitInterceptor"), "windows")).clear();
    }

    private void useAuthQuota() {
        for (int i = 0; i < 3; i++)
            assertEquals(200, post("/api/auth/login", null, "{\"username\":\"public-quota-probe-" + i + "\",\"password\":\"invalid\"}").status());
        assertEquals(429, post("/api/auth/login", null, "{\"username\":\"public-quota-over\",\"password\":\"invalid\"}").status());
    }

    private void usePublicQuota() {
        for (int i = 0; i < 3; i++) assertEquals(200, get("/api/stations/public", null).status());
        Api blocked = get("/api/stations/public", null);
        assertEquals(429, blocked.status()); assertEquals(1, blocked.code()); assertFalse(blocked.message().isBlank());
    }

    @Test void aliasesAndPublicRoutesShareOneQuota() {
        assertEquals(200, get("/api/stations/public", null).status());
        assertEquals(200, get("/api/station/search?keyword=none", null).status());
        assertEquals(200, get("/api/stations/1/status", null).status());
        for (String path : new String[]{"/api/stations/public", "/api/station/public", "/api/stations/search", "/api/station/search",
                "/api/stations/1/public-phone", "/api/station/1/public-phone", "/api/stations/1/status", "/api/station/1/status"}) {
            Api result = get(path, null);
            assertEquals(429, result.status(), path); assertEquals(1, result.code(), path);
        }
    }

    @Test void exhaustingPublicQuotaDoesNotConsumeAuthenticationQuota() { usePublicQuota(); useAuthQuota(); }
    @Test void exhaustingAuthenticationQuotaDoesNotConsumePublicQuota() { useAuthQuota(); usePublicQuota(); }

    @Test void livenessRemainsAvailableAndConsumesNeitherQuota() {
        for (int i = 0; i < 10; i++) assertEquals(0, get("/api/system/health", null).code());
        useAuthQuota(); usePublicQuota();
        for (int i = 0; i < 10; i++) assertEquals(0, get("/api/system/health", null).code());
    }

    @Test void rotatingForwardedHeadersCannotBypassPublicQuota() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 4; i++) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stations/public"))
                    .header("X-Forwarded-For", "192.0.2." + (i + 1))
                    .header("X-Real-IP", "198.51.100." + (i + 1))
                    .header("Forwarded", "for=203.0.113." + (i + 1)).GET().build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(i < 3 ? 200 : 429, result.statusCode());
            if (i == 3) assertEquals(1, om.readTree(result.body()).path("code").asInt());
        }
    }
}
