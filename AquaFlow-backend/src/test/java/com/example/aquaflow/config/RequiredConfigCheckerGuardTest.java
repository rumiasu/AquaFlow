package com.example.aquaflow.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「数据源必需变量」启动期强校验的护栏回归（2026-09-30 拍板 A）。
 *
 * <p>为什么值得一个测试：这道校验防的是一次<b>实测过的真实失效</b> ——
 * {@code application-prod.yml} 里 {@code url: ${DB_URL}}（无默认值）看着像"缺了会拒启"，
 * 实际 {@code spring.datasource.url} 启动期没人解析（Hikari 懒初始化），摘掉 DB_URL
 * 服务照样起来，到首个查库请求才 500（见 {@code scripts/prod-startup-check.js} 头注）。
 * 防护是 {@code @PostConstruct} 里一段手写判据，删掉/改坏时编译器与业务测试都发现不了，
 * 只有钉成断言才拦得住（同 {@code PasswordInitializerGuardTest} 的思路）。</p>
 *
 * <p>端到端的 13 场景验证在 {@code scripts/prod-startup-check.js}（拿发布物 jar 真起），
 * 本测试只钉"判据本身没被拿掉、prod/非 prod 边界正确"。</p>
 */
class RequiredConfigCheckerGuardTest {

    /** 备好一个"除数据源外一切齐备"的 checker：前置校验（JWT/微信）全过，只测数据源这一道。 */
    private RequiredConfigChecker checker(MockEnvironment environment) {
        RequiredConfigChecker c = new RequiredConfigChecker();
        ReflectionTestUtils.setField(c, "jwtSecret", "01234567890123456789012345678901");
        ReflectionTestUtils.setField(c, "wxAppId", "wx-test-appid");
        ReflectionTestUtils.setField(c, "wxSecret", "wx-test-secret");
        ReflectionTestUtils.setField(c, "environment", environment);
        return c;
    }

    @Test
    @DisplayName("prod 下数据源占位符未解析（缺 DB_URL）必须拒启")
    void prodUnresolvedUrlRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.url", "${DB_URL}");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "pw");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> checker(env).check(),
                "占位符没展开却放行 —— 服务会带病启动，首个查库请求才 500");
        assertTrue(ex.getMessage().contains("DB_URL"),
                "错误信息必须点名 DB_URL（prod-startup 场景11 的 marker 判据靠它），实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("prod 下数据源三件齐备时通过")
    void prodResolvedConfigPasses() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/aquaflow");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "pw");

        assertDoesNotThrow(() -> checker(env).check(),
                "全解析的配置被误杀 —— 会把正常生产环境挡在门外");
    }

    @Test
    @DisplayName("非 prod（local/test）不校验数据源 —— 本地不读 DB_URL 环境变量")
    void nonProdSkipsDatasourceCheck() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/aquaflow");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "");

        assertDoesNotThrow(() -> checker(env).check(),
                "local 不读环境变量 DB_URL（application-local.yml 写死 url），全局硬校验会把本地与 CI 全打红");
    }
}
