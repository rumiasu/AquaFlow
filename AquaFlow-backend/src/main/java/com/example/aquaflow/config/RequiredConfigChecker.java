package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期安全配置校验（fail-fast）。
 * <p>
 * 背景：此前 application.yml 中明文硬编码了微信 AppSecret、腾讯云 COS 密钥与 JWT 签名密钥，
 * 且已提交进 Git 历史。硬编码默认值 -> 任何人拿到仓库即可伪造"任意水站站长"的 JWT。
 * 这里把所有敏感配置的默认值清空，并在启动阶段强制校验：缺失直接拒绝启动，
 * 避免带着空密钥上线（空 JWT 密钥 = 签名可被伪造）。
 * </p>
 * 本地开发：使用环境变量，或新建 application-local.yml 并以 --spring.profiles.active=local 启动。
 */
@Slf4j
@Component
public class RequiredConfigChecker {

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${wechat.miniapp.appid:}")
    private String wxAppId;

    @Value("${wechat.miniapp.secret:}")
    private String wxSecret;

    // [2026-09-15] 客户端与员工端是两个小程序（appid 不同）。员工端这对**不硬校验**：
    // 缺了只影响"员工端微信登录"，本地还有 dev-login 兜底，没必要让人起不来。
    @Value("${wechat.miniapp.staff-appid:}")
    private String wxStaffAppId;

    @Value("${wechat.miniapp.staff-secret:}")
    private String wxStaffSecret;

    @Value("${cos.secret-id:}")
    private String cosSecretId;

    @Value("${cos.secret-key:}")
    private String cosSecretKey;

    @PostConstruct
    public void check() {
        List<String> missing = new ArrayList<>();

        if (isBlank(jwtSecret)) {
            missing.add("JWT_SECRET");
        } else if (jwtSecret.length() < 32) {
            missing.add("JWT_SECRET（长度需 >= 32 位）");
        }
        if (isBlank(wxAppId)) {
            missing.add("WX_APP_ID");
        }
        if (isBlank(wxSecret)) {
            missing.add("WX_APP_SECRET");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "缺少必需的安全配置：" + String.join("、", missing)
                            + "。请通过环境变量注入（禁止写回 application.yml），"
                            + "参考 AquaFlow-backend/.env.example");
        }

        if (isBlank(cosSecretId) || isBlank(cosSecretKey)) {
            log.warn("COS_SECRET_ID / COS_SECRET_KEY 未配置，对象存储上传功能将不可用（不影响启动）");
        }
        if (isBlank(wxStaffAppId) || isBlank(wxStaffSecret)) {
            log.warn("WX_STAFF_APP_ID / WX_STAFF_APP_SECRET 未配置，员工端（站长/配送员）微信登录不可用"
                    + "（不影响启动，客户端微信登录不受影响；本地可用 dev-login 兜底）");
        }
        log.info("安全配置校验通过：JWT / 客户端微信配置均已外部注入");
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
