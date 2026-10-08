package com.example.aquaflow.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开水站发现面的独立连接 IP 配额；认证预算和健康探针不共享此计数器。
 * [2026-10-05] 原匿名列表/搜索/电话可无限重复读取；跨路径、别名必须聚合，不能换路径重获额度。
 * 与认证限流一样不信任转发头；前置地址重写仍由 RateLimitInterceptor 的启动护栏拒绝。
 * 进程内配额只适用于单实例，代理后用户共享预算；多实例须改集中式计数。
 */
@Component
public class PublicRateLimitInterceptor implements HandlerInterceptor {
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_ADDRESSES = 10_000;
    private static final long CLEANUP_INTERVAL_MS = 30_000L;
    // Admission/counting/cleanup share this map monitor: no detached active counters.
    private long nextCleanup;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    @Value("${app.rate-limit.public-enabled:true}") private boolean enabled;
    @Value("${app.rate-limit.public-per-minute:60}") private int limitPerMinute;

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled || limitPerMinute <= 0) return true;
        String remote = request.getRemoteAddr();
        String ip = remote == null || remote.isBlank() ? "unknown" : remote;
        long now = System.currentTimeMillis();
        boolean allowed = false;
        synchronized (windows) {
            if (now >= nextCleanup) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().start >= 2 * WINDOW_MS);
                nextCleanup = now + CLEANUP_INTERVAL_MS;
            }
            Window window = windows.get(ip);
            if (window == null && windows.size() < MAX_ADDRESSES) {
                window = new Window(now);
                windows.put(ip, window);
            }
            // Capacity pressure rejects new addresses; never evict a live exhausted IP.
            if (window != null) {
                if (now - window.start >= WINDOW_MS) { window.start = now; window.count = 0; }
                if (window.count < limitPerMinute) { window.count++; allowed = true; }
            }
        }
        if (allowed) return true;
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":1,\"message\":\"查询过于频繁，请稍后再试\",\"data\":null}");
        response.getWriter().flush();
        return false;
    }

    private static final class Window {
        private volatile long start;
        private int count;
        private Window(long start) { this.start = start; }
    }
}
