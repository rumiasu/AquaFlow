package com.example.aquaflow.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录类端点的按 IP 限流（防撞库 / 防用户名枚举）。
 *
 * <p><b>为什么需要它</b>：{@code LoginController.login} 已有的 [AQ-040] 锁定是
 * <b>按用户名</b>计数的（15 分钟内同一用户名失败超限即锁）。攻击者换用户名继续试
 * 就能绕开它 —— 既能枚举出哪些账号存在，又不受任何速度限制。
 * 本拦截器补的是<b>按来源 IP</b>的那一层，两层正交、都要有。</p>
 *
 * <p><b>为什么不用 Redis</b>：单机部署（本地笔记本当服务器）下进程内计数就够，
 * 引入 Redis 属于为不存在的规模付运维成本。⚠️ 一旦多实例部署，本实现会退化成
 * 「每实例各限一份」—— 那时必须换成集中式计数器（Redis INCR + EXPIRE）。</p>
 *
 * <p><b>响应约定</b>：超限返回 HTTP 429 + 仍是 {@code {code:1,message:...}} 的 JSON。
 * 只给 429 不给 body 会让小程序拿不到可展示的文案；只给 code=1 不给 429 则无法被
 * 网关/日志按状态码统计。两者都给，前端两种判法都能正确处理。</p>
 *
 * <p><b>计数粒度是「按 IP 聚合」而不是「按 IP+端点」</b>：这是刻意选择的更严口径 ——
 * 分开计数的话，攻击者把 20 次配额分摊到 login / wx-login / refresh 上就能放大三倍。
 * 代价是同一出口 IP（如公司 NAT）下的多名员工共享一份配额，20/分钟对真实登录足够宽裕。</p>
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 固定窗口长度：1 分钟。窗口越短越平滑，但计数误差越大；登录场景 1 分钟足够。 */
    private static final long WINDOW_MS = 60_000L;

    /** 超过这个数量的 IP 才触发一次过期清理，避免每次请求都遍历 map。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.auth-per-minute:20}")
    private int limitPerMinute;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled || limitPerMinute <= 0) {
            return true;
        }

        String ip = clientIp(request);
        long now = System.currentTimeMillis();

        Window window = windows.computeIfAbsent(ip, k -> new Window(now));
        boolean allowed;
        int current;
        synchronized (window) {
            if (now - window.start >= WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            window.count++;
            current = window.count;
            allowed = current <= limitPerMinute;
        }

        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().start >= 2 * WINDOW_MS);
        }

        if (!allowed) {
            log.warn("[限流] 登录类端点请求过于频繁: ip={}, path={}, count={}/{}",
                    ip, request.getRequestURI(), current, limitPerMinute);
            writeTooManyRequests(response);
            return false;
        }
        return true;
    }

    /**
     * 取真实来源 IP。
     * <p>只有部署在受信任的反向代理后面时才认 {@code X-Forwarded-For}；本机直连时它可被客户端伪造，
     * 而伪造的代价只是"换一个 key 继续请求"—— 所以这里取首跳，并在生产上依赖网关覆写该头。</p>
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":1,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}");
        response.getWriter().flush();
    }

    /** 固定窗口计数。可变字段只在持有该对象监视器时读写。 */
    private static final class Window {
        private long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
