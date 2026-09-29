package com.example.aquaflow.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「默认口令」这颗雷的护栏回归（2026-09-29 关雷）。
 *
 * <p>为什么值得一个测试：原来的实现是「无密码 ⇒ 发 admin123/123456」且<b>没有 profile 限制</b> ——
 * 而微信建号（wx-login-staff）本来就 password_hash 为 NULL，等于每次启动都给全员发公开口令。
 * 这类"注解被顺手删掉、行为无声退回"的退化，编译器和业务测试都发现不了，
 * 只有把约束钉成断言才拦得住（同 {@code ManagerOrderControllerRemovedIntegrationTest} 的思路）。</p>
 *
 * <p>配套行为用例见 {@code integration/PasswordInitializerIntegrationTest}（补密码 + 不覆盖已有密码）。</p>
 */
class PasswordInitializerGuardTest {

    @Test
    @DisplayName("PasswordInitializer 必须排除 prod profile")
    void excludedFromProd() {
        Profile profile = PasswordInitializer.class.getAnnotation(Profile.class);
        assertNotNull(profile, "@Profile 被拿掉了 —— prod 会重新拿到 admin123/123456 这两个人人皆知的口令");
        assertTrue(List.of(profile.value()).contains("!prod"),
                () -> "@Profile 的值是 " + Arrays.toString(profile.value())
                        + "，必须包含 \"!prod\"（prod 下本 Bean 根本不该存在，同 DevLoginController）");
    }

    // 原「必须保留 app.password-initializer.enabled 开关」用例已随 2026-09-29 拍板 A 删除：
    // 开关本身被拍板移除（生产放弃密码登录），保留它 = 测试钉住一个已废配置。
}
