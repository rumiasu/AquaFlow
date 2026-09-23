package com.example.aquaflow.integration;

import com.example.aquaflow.exception.GlobalExceptionHandler;
import com.example.aquaflow.support.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v30 · 系统告警的**外部渠道实际投递**（{@code alert.system-webhook}）。
 *
 * <p>为什么单独立一个用例：{@code AlertRoutingIntegrationTest} 只验证了「告警落在哪张表、
 * 谁能看见」，而**真正把告警送到系统管理员手上的那一步（HTTP 投递）此前没有任何覆盖**——
 * 它是本模块唯一的"主动通知"出口，坏掉了只会表现为"告警都在库里，但没人收到"，
 * 而且因为 {@code AlertServiceImpl} 刻意把投递失败吞成 FAILED（不连累业务），
 * 失败也不会让任何业务用例变红。属于典型的"静默失效"路径。</p>
 *
 * <p>做法：起一个本地 HTTP 桩（{@code HttpServer}，端口 0 由系统分配），用
 * {@link DynamicPropertySource} 把 {@code alert.system-webhook} 指过去，
 * 触发一次真实系统故障，断言①桩收到请求、②{@code notify_status} 被回写成 {@code PUSHED}。</p>
 *
 * <p>⚠️ 桩必须在**静态初始化块**里启动：{@code @DynamicPropertySource} 的取值发生在
 * Spring 容器创建阶段（早于 {@code @BeforeAll}），若放在 {@code @BeforeAll} 里，
 * 注册到容器里的会是 null。</p>
 */
@DisplayName("v30 · 系统告警外部渠道（webhook）真的发出去了")
class AlertWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final HttpServer HOOK;
    private static final String HOOK_URL;
    /** 桩收到的请求体（投递可能是并发的，用同步 List 兜住） */
    private static final List<String> RECEIVED = Collections.synchronizedList(new ArrayList<>());

    /**
     * 桩的返回体，可按用例切换。默认用企业微信**成功**时的真实形态
     * （{@code {"errcode":0,"errmsg":"ok"}}），而不是空体 —— 空体会让"业务码校验"这条分支测不到。
     */
    private static volatile String stubBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    static {
        HttpServer server = null;
        String url = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] out = stubBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        } catch (IOException e) {
            // 桩起不来就让整个类失败，而不是退化成"跳过" —— 跳过会伪装成通过
            throw new ExceptionInInitializerError(e);
        }
        HOOK = server;
        HOOK_URL = url;
    }

    @AfterAll
    static void stopHook() {
        if (HOOK != null) {
            HOOK.stop(0);
        }
    }

    /**
     * 把外部渠道指向本地桩。注意这会改变属性集 → Spring 会为本类单独建一个容器
     * （与其他用例的容器互不影响，也不会污染它们的 notify_status 断言）。
     */
    @DynamicPropertySource
    static void webhookProperty(DynamicPropertyRegistry registry) {
        registry.add("alert.system-webhook", () -> HOOK_URL);
    }

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @Autowired
    private com.example.aquaflow.service.AlertService alertService;

    @Test
    @DisplayName("系统故障告警会 POST 到外部渠道，并把 notify_status 回写成 PUSHED")
    void systemAlertIsActuallyPushed() throws InterruptedException {
        int before = RECEIVED.size();

        globalExceptionHandler.handleRuntimeException(new RuntimeException("boom-webhook-test"));

        // pushSystemWebhook 是同步调用，正常此刻已完成；留一点余量以防将来改成异步
        for (int i = 0; i < 50 && RECEIVED.size() == before; i++) {
            Thread.sleep(100);
        }

        assertTrue(RECEIVED.size() > before,
                "外部渠道没收到投递 —— 告警会静默地只留在库里，没人被通知。收到=" + RECEIVED);
        String body = RECEIVED.get(RECEIVED.size() - 1);
        assertTrue(body.contains("未预期的服务端异常") || body.contains("boom-webhook-test"),
                "投递内容应包含告警标题/详情，实际=" + body);

        assertEquals("PUSHED",
                jdbc.queryForObject("SELECT notify_status FROM alert_log WHERE alert_type='SYSTEM' "
                        + "ORDER BY id DESC LIMIT 1", String.class),
                "投递成功应把 notify_status 回写为 PUSHED（LOGGED 只用于'没有渠道可推'）");
    }

    @Test
    @DisplayName("渠道回 HTTP 200 但业务码非 0（如企微 errcode=93000）→ 必须记 FAILED，不能记成 PUSHED")
    void channelRejectionIsNotReportedAsPushed() {
        stubBody = "{\"errcode\":93000,\"errmsg\":\"webhook 已被移除或 key 失效\"}";
        try {
            globalExceptionHandler.handleRuntimeException(new RuntimeException("boom-rejected"));

            // 企业微信/钉钉/Server酱 失败时都是 HTTP 200 + body 里的错误码。
            // 若只判 HTTP 状态，这里会记成 PUSHED —— 于是"以为通知发出去了、其实一条没到"，
            // 而且因为投递失败被刻意吞掉，不会有任何用例变红。故必须锁死这一条。
            assertEquals("FAILED",
                    jdbc.queryForObject("SELECT notify_status FROM alert_log WHERE alert_type='SYSTEM' "
                            + "ORDER BY id DESC LIMIT 1", String.class),
                    "渠道业务码非 0 必须判失败（HTTP 200 不代表推送成功）");
        } finally {
            stubBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";
        }
    }

    @Test
    @DisplayName("运营告警不推外部渠道：站长侧渠道尚未接入，状态保持 LOGGED 而不是 FAILED")
    void operationAlertStaysLogged() {
        long station = createStation("S1");
        alertService.stationFault(station, "WARN", "TestSource", "桶异常待处置", "测试详情", null, null);

        // 必须真实产生一条运营告警再断言：AbstractIntegrationTest 每个用例都会 TRUNCATE，
        // 若只查"是否存在非 LOGGED 的行"，表是空的 → 恒成立 → 是个假通过的空断言。
        assertEquals("LOGGED",
                jdbc.queryForObject("SELECT notify_status FROM alert_log WHERE alert_type='OPERATION'",
                        String.class),
                "运营告警没有外部渠道（站长侧微信订阅消息尚未接入）→ 只能是 LOGGED"
                        + "（= 已落库、站点可查），不得记成 FAILED，否则会把'没渠道'误报成'推送失败'");
    }
}
