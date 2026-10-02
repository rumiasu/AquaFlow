package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 登录类端点的按 IP 限流（矩阵 C4，2026-09-16 新增实现 + 用例）。
 *
 * <p>整套集成测试默认<b>关闭</b>限流（见 {@code application-test.yml} 的注释：所有用例都从
 * 127.0.0.1 发请求，开着会互相干扰）。本类用 {@code @TestPropertySource} 单独把开关打开、
 * 阈值压到 3，因此它会启用一个独立的 Spring 上下文。</p>
 *
 * <p>用例断言的核心是「两层防护正交」：按用户名锁定 [AQ-040] 对<b>每次换用户名</b>的
 * 撞库毫无作用，必须由按 IP 的这一层兜住。</p>
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-per-minute=3"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    /** 窗口内所有断言必须放在同一个用例里 —— 计数器是进程内的，跨用例会互相污染。 */
    @Test
    @DisplayName("同一 IP 换用户名连撞 3 次后被 429 挡住，而非登录端点不受影响")
    void loginIsRateLimitedPerIpEvenWhenUsernameChanges() {
        // 前 3 次：每次都换一个用户名。若只有 [AQ-040] 的按用户名锁定，这三次都会是
        // "用户名或密码错误"（每个 key 只失败 1 次，远未触发锁定）——正是撞库的形状。
        for (int i = 0; i < 3; i++) {
            Api attempt = post("/api/auth/login", null,
                    "{\"username\":\"probe-" + i + "\",\"password\":\"whatever\"}");
            assertNotEquals(429, attempt.status(), "第 " + (i + 1) + " 次不该被限流: " + attempt);
            assertNotEquals(0, attempt.code(), "凭据是假的，必须失败: " + attempt);
        }

        // 第 4 次：超过阈值 3 → 429，且仍带可展示的 body
        Api blocked = post("/api/auth/login", null, "{\"username\":\"probe-3\",\"password\":\"whatever\"}");
        assertEquals(429, blocked.status(), "超过阈值必须 429: " + blocked);
        assertEquals(1, blocked.code(), "429 也必须给小程序可展示的 code/message: " + blocked);
        assertFalse(blocked.message().isBlank(), "限流文案不能为空");

        // 换端点绕不过（计数按 IP 聚合，不是按端点）
        assertEquals(429, post("/api/auth/refresh", null, "{\"refreshToken\":\"bogus\"}").status(),
                "换个限流端点继续打仍应被挡住");

        // [F-48] 注册表全钉：WebMvcConfig 注册的 7 条限流路径必须**逐条**断言 429。
        // 过去这里只打 login + refresh 两条 ⇒ 删掉其余 5 条注册根本不会红（台账 F-48）。
        // 本类是单用例（计数器按 IP 聚合、跨用例互相污染），所以整表断言放在同一方法里；
        // 「注册表清单 == 本文件清单」的双向对账另由 scripts/check-ledger-claims.js 的 FND-4 盯。
        // ⚠️ dev-login 的 Bean 在测试 profile 不存在（dev-login-enabled=false），但限流拦截器
        //    按**路径**匹配、先于 handler 命中 ⇒ 仍应 429，这正是要钉住的行为。
        String[] registered = {
                "/api/auth/login",
                "/api/auth/wx-login",
                "/api/auth/wx-login-staff",
                "/api/auth/dev-login",
                "/api/auth/refresh",
                "/api/auth/change-password",
                "/api/auth/bind-staff"
        };
        for (String path : registered) {
            Api r = post(path, null, "{}");
            assertEquals(429, r.status(), "限流注册表路径超限后必须 429: " + path + " -> " + r);
        }

        // 未被限流覆盖的端点不受影响：限流只挂在登录类路径上
        long customer = createCustomer("限流客户", "ratelimit-openid");
        Api normal = get("/api/products/on-sale", customerToken(customer));
        assertEquals(0, normal.code(), "普通业务端点不该被登录限流误伤: " + normal);
    }
}
