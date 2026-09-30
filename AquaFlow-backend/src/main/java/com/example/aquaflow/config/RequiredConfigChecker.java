package com.example.aquaflow.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;

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
 * <p>
 * ⚠️ 三类校验别混（[F-06] + [2026-09-30 拍板 A]）：
 * <ul>
 *   <li>本类是<b>启动期硬校验</b>：读的是<b>解析后</b>的配置值，故能覆盖命令行参数 /
 *       SPRING_APPLICATION_JSON / 环境变量等一切来源。</li>
 *   <li>{@code application-prod.yml} 里写 {@code ${X}}（无默认值）对 <b>有人读的键</b>
 *       （jwt / wechat / cors 由本类 {@code @Value} 读）是 Spring 解析期失败：
 *       那条路管"缺变量即拒启"，管不了"变量被设成了危险的值"。</li>
 *   <li>但 {@code spring.datasource.*} <b>启动期没有任何代码读</b>（Hikari 懒初始化，
 *       2026-09-30 实测摘掉 DB_URL 服务照样 Started、连接池首个查库请求才建立）
 *       ⇒ yml 里写 {@code ${DB_URL}} 拦不住缺配置，由 {@link #checkProdDatasource()} 显式兜底。
 *       别再把"占位符无默认值"当成数据源的护栏。</li>
 * </ul>
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

    // [F-06 2026-09-30] 两个「生产后门开关」。默认值与 application.yml 保持一致（都是 false），
    // 这里读的是**解析后**的值，故能看见命令行 / 环境变量 / SPRING_APPLICATION_JSON 的覆盖。
    // ⚠️ 不要在字段上写死 true 的默认值：本地/测试要靠 application-local.yml 与
    //    @TestPropertySource 打开它们（见 MockWechatPayIntegrationTest）。
    @Value("${app.payment.mock-wechat-pay:false}")
    private boolean mockWechatPay;

    @Value("${app.dev-login-enabled:false}")
    private boolean devLoginEnabled;

    /** 用于判断 prod profile 是否生效（见 {@link #checkProdSafetySwitches()}），不读 properties 文件。 */
    @Autowired
    private Environment environment;

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

        // [F-06] 缺配置查完之后立刻查"生产有没有开后门"——这是全仓最严重的一类误配
        // （模拟支付开着 = 零元购），不能等到跑起来才发现。
        checkProdSafetySwitches();

        // [2026-09-30 拍板 A] 数据源三件 prod 强校验（缺了在启动期拒，不留到首个查库请求才炸）。
        checkProdDatasource();

        if (isBlank(cosSecretId) || isBlank(cosSecretKey)) {
            log.warn("COS_SECRET_ID / COS_SECRET_KEY 未配置，对象存储上传功能将不可用（不影响启动）");
        }
        if (isBlank(wxStaffAppId) || isBlank(wxStaffSecret)) {
            log.warn("WX_STAFF_APP_ID / WX_STAFF_APP_SECRET 未配置，员工端（站长/配送员）微信登录不可用"
                    + "（不影响启动，客户端微信登录不受影响；本地可用 dev-login 兜底）");
        }
        log.info("安全配置校验通过：JWT / 客户端微信配置均已外部注入");
    }

    /**
     * [F-06 2026-09-30] prod profile 生效时，两个「后门开关」必须为 false，否则<b>拒绝启动</b>。
     *
     * <p><b>为什么必须在启动期判、而不是只靠 yml</b>：{@code app.payment.mock-wechat-pay}
     * 在 {@code application-prod.yml} 里<b>没有</b>覆盖项（只有 {@code application.yml} 的
     * {@code ${MOCK_WECHAT_PAY:false}}），于是环境变量一设就真开着，全站「微信支付」点一下就成功
     * = 零元购；{@code app.dev-login-enabled} 虽然 prod yml 写死了 false，但
     * <b>命令行参数 / {@code SPRING_APPLICATION_JSON} 的优先级高于配置文件</b>，照样能被覆盖。
     * 本方法读的是 Spring <b>解析后的</b>值，是唯一能同时覆盖这几条来源的闸。</p>
     *
     * <p><b>为什么只在 prod 生效</b>：这两个开关在本地/测试里<b>必须</b>能打开
     * （{@code application-local.yml} 开模拟支付、用例的 {@code @TestPropertySource} 设 true），
     * 所以判据是"prod 生效时不许为 true"，不是"全局不许为 true"。
     * profile 判断走 {@link Environment#matchesProfiles(String...)}（活跃 profile 为空时才回退到
     * {@code spring.profiles.default}，本仓默认是 local）。</p>
     *
     * <p>与 {@code DevLoginController} 的 {@code @Profile("!prod")} 是两道闸：那个注解让 prod
     * 根本不创建后门端点；这里再拦一道，防的是"将来有人摘掉那个注解"或"把后门开关当普通配置改"。</p>
     */
    private void checkProdSafetySwitches() {
        if (!environment.matchesProfiles("prod")) {
            return;
        }

        List<String> enabled = new ArrayList<>();
        if (mockWechatPay) {
            enabled.add("app.payment.mock-wechat-pay=true —— 全站「微信支付」点一下就成功 = 零元购");
        }
        if (devLoginEnabled) {
            enabled.add("app.dev-login-enabled=true —— 开放免微信授权的后门登录");
        }
        if (!enabled.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境（profile=prod）禁止开启以下开关：" + String.join("；", enabled)
                            + "。这两个开关在生产必须为 false。请检查环境变量 MOCK_WECHAT_PAY / DEV_LOGIN_ENABLED、"
                            + "命令行参数（--app.xxx=true）与 SPRING_APPLICATION_JSON；"
                            + "本校验读的是解析后的最终值，改 application-prod.yml 绕不过去。"
                            + "参考 AquaFlow-backend/.env.example");
        }
        log.info("生产安全开关校验通过：模拟微信支付与 dev-login 均已关闭");
    }

    /**
     * [2026-09-30 拍板 A] prod 下数据源三件（DB_URL / DB_USERNAME / DB_PASSWORD）启动期强校验。
     *
     * <p><b>为什么不能只靠 {@code application-prod.yml} 的 {@code ${DB_URL}}（无默认值）</b>：
     * 2026-09-30 实测（{@code scripts/prod-startup-check.js} 场景「摘掉 DB_URL」）——
     * {@code spring.datasource.url} 的占位符<b>启动期没人解析</b>：Hikari 懒初始化，
     * 摘掉 DB_URL 服务照样打印 {@code Started AquaFlowApplication}、{@code processlist}
     * 连接数为 0，连接池要到<b>首个查库请求</b>才建立 ⇒ 后果是"进程活着、健康检查过、
     * 全站查库接口 500"，比拒启更糟。jwt / wechat / cors 那几项之所以真的会炸，
     * 是因为本类 {@code @Value} 在启动期读了它们；数据源没人读，所以由这里显式读一次。</p>
     *
     * <p><b>判据</b>：解析后的值为空、或仍含 {@code ${…}} 占位符（= 环境变量没给）
     * ⇒ 拒启。错误信息<b>只报变量名、不回显值</b>（url 与密码都可能带敏感片段）。</p>
     *
     * <p><b>只在 prod 生效</b>：local / test 不读环境变量 DB_URL
     * （{@code application-local.yml} 写死 url、测试库走 {@code TEST_DB_URL} 覆盖），
     * 全局硬校验会把本地与 CI 全打红。</p>
     */
    private void checkProdDatasource() {
        if (!environment.matchesProfiles("prod")) {
            return;
        }

        List<String> bad = new ArrayList<>();
        requireResolved("spring.datasource.url", "DB_URL", bad);
        requireResolved("spring.datasource.username", "DB_USERNAME", bad);
        requireResolved("spring.datasource.password", "DB_PASSWORD", bad);
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境数据源配置不可用：" + String.join("、", bad)
                            + " —— 值缺失，或仍是未解析的占位符（形如 ${...}）。"
                            + "请通过环境变量注入（禁止写回 application.yml），参考 AquaFlow-backend/.env.example");
        }
        log.info("生产数据源配置校验通过：DB_URL / DB_USERNAME / DB_PASSWORD 均已外部注入");
    }

    /**
     * 读一个配置键并判"是否真的解析出来了"（只记变量名，不回显值）。
     *
     * <p>Spring 7 起 {@code getProperty} 遇到无源占位符会直接抛
     * {@link org.springframework.util.PlaceholderResolutionException}（不再返回原文）——
     * 捕获它归并进清单，三件缺失在一条错误里一次报全。</p>
     */
    private void requireResolved(String key, String varName, List<String> bad) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (PlaceholderResolutionException ex) {
            bad.add(varName);
            return;
        }
        if (isBlank(value) || value.contains("${")) {
            bad.add(varName);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
