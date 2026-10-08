package com.example.aquaflow.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PublicRateLimitInterceptorTest {
    private PublicRateLimitInterceptor limiter() {
        var limiter = new PublicRateLimitInterceptor();
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "limitPerMinute", 1);
        return limiter;
    }
    private boolean call(PublicRateLimitInterceptor limiter, String ip) throws Exception {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        boolean accepted = limiter.preHandle(request, response, null);
        if (!accepted) { assertEquals(429, response.getStatus()); assertTrue(response.getContentAsString().contains("\"code\":1")); }
        return accepted;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> windows(PublicRateLimitInterceptor limiter) {
        return (Map<String, Object>) ReflectionTestUtils.getField(limiter, "windows");
    }
    private void fill(PublicRateLimitInterceptor limiter, int count) throws Exception {
        for (int i = 0; i < count; i++) assertTrue(call(limiter, "fixture-" + i));
    }
    @Test void activeCapacityIsHardBoundedAndDoesNotResetExistingCounters() throws Exception {
        var limiter = limiter(); fill(limiter, 10_000);
        for (int i = 0; i < 30; i++) assertFalse(call(limiter, "overflow-" + i));
        assertEquals(10_000, windows(limiter).size());
        assertFalse(call(limiter, "fixture-0"), "overflow must not evict a live exhausted counter");
    }
    @Test void expiredEntriesCanBeReclaimedWithThrottledSweeps() throws Exception {
        var limiter = limiter(); fill(limiter, 10_000);
        Object stale = windows(limiter).get("fixture-0");
        ReflectionTestUtils.setField(stale, "start", System.currentTimeMillis() - 120_001);
        ReflectionTestUtils.setField(limiter, "nextCleanup", 0L);
        assertTrue(call(limiter, "replacement"));
        assertEquals(10_000, windows(limiter).size()); assertFalse(windows(limiter).containsKey("fixture-0"));
        long next = (long) ReflectionTestUtils.getField(limiter, "nextCleanup");
        for (int i = 0; i < 30; i++) assertFalse(call(limiter, "blocked-" + i));
        assertEquals(next, ReflectionTestUtils.getField(limiter, "nextCleanup"), "full table must not trigger a scan per request");
        assertFalse(call(limiter, "fixture-1"));
    }
    @Test void concurrentNewAddressesCannotOverrunTheLastSlot() throws Exception {
        var limiter = limiter(); fill(limiter, 9_999);
        var pool = Executors.newFixedThreadPool(16); var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 32; i++) {
                String ip = "concurrent-" + i;
                results.add(pool.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return call(limiter, ip); }));
            }
            start.countDown(); int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(10_000, windows(limiter).size());
            assertFalse(call(limiter, "fixture-0"));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
