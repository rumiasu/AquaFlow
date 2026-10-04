package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 可注入业务时钟 · 跨零点口径能被钉死（F-16）。
 *
 * <p><b>这个用例要证明的不是"某段代码算得对"，而是"口径从此可以固定"</b>：
 * 报表类用例原先只能拿 {@code LocalDate.now()} 自己算区间端点，而"算端点"与"造数"之间
 * 只要跨过一次零点，端点就落到前一天、数据落到后一天 —— 偶发红且无法复现。
 * 现在时间源是 {@code config/ClockConfig} 的 {@link Clock} Bean，本类把它换成一个
 * <b>可前后拨动</b>的时钟，于是"23:59:30 → 00:00:30"这件事可以在一个用例里确定性地发生。</p>
 *
 * <p><b>为什么冻结在 2099 年</b>：① 与真实时间不可能重合 —— 谁把注入时钟摘掉、
 * 改回直连 {@code LocalDate.now()}，这里的断言必红（这是本用例的反向验证）；② 账期用例会写出
 * 2099 年的应付日期，绝不会被「欠款即停」（{@code due_date < CURDATE()}）判成逾期而干扰断言。</p>
 *
 * <p>⚠️ <b>本仓测试不用 Mockito</b>（理由见 {@code ScheduledJobFailureAlertTest} 的类注释：
 * 受限环境下 byte-buddy 的 mock maker 起不来），所以换 Bean 走
 * {@code @TestConfiguration} + {@code @Primary}，不引入新的测试依赖。</p>
 *
 * <p>⚠️ <b>已知边界（诚实记一笔）</b>：本时钟只覆盖 <b>Java 侧</b>取时间。SQL 里的
 * {@code NOW()} / {@code CURDATE()}（例如 {@code bindCodeMapper.findUsableStaffId} 的码有效期校验、
 * {@code offlinePaymentBlockReason} 的逾期判据）读的仍是<b>库的时间</b>，不受本时钟影响。
 * 因此"冻结时钟"的用例不要依赖 SQL 日期函数做断言 —— 两套时钟只在对齐时等价。</p>
 */
// 2026-10-03：继承基类上下文时显式导入嵌套测试配置，避免回落真实日期让跨月用例失去冻结时钟。
@Import(BusinessClockIntegrationTest.TestClockConfig.class)
@DisplayName("可注入业务时钟 · 跨零点口径能被钉死（F-16）")
class BusinessClockIntegrationTest extends AbstractIntegrationTest {

    /** 业务时区固定 +08:00：本仓面向中国水站，测试时钟显式带时区，不跟 CI 机器的 JVM 默认走。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 零点前一刻：2099-03-31 23:59:30（+08:00）。 */
    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2099-03-31T15:59:30Z");

    /** 同一个用例里拨过零点后的那一刻：2099-04-01 00:00:30（+08:00）。 */
    private static final Instant AFTER_MIDNIGHT = Instant.parse("2099-03-31T16:00:30Z");

    /** 整个 Spring 上下文共用这一个可拨动时钟 —— 生产代码注入的就是它。 */
    private static final MutableClock CLOCK = new MutableClock(ZONE, BEFORE_MIDNIGHT);

    /**
     * 用 {@code @Primary} 顶掉 {@code config/ClockConfig} 的默认时钟。
     * 两个 {@code Clock} Bean 同时存在，注入点（{@code util/BusinessTime}）按 primary 取值。
     */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock businessClock() {
            return CLOCK;
        }
    }

    /** 每个用例都从"零点前一刻"开始；基类的 {@code resetDatabase()} 先跑（父类 @BeforeEach 在子类之前）。 */
    @BeforeEach
    void freezeBeforeMidnight() {
        CLOCK.set(BEFORE_MIDNIGHT);
    }

    @Test
    @DisplayName("看板的「今天 / 近7天」跟着注入时钟走：拨过 00:00 整体后移一天")
    void dashboardTodayFollowsInjectedClockAcrossMidnight() {
        long station = createStation("时钟站");
        long manager = createStaff("时钟站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // ---- 零点前：2099-03-31 23:59:30 ----
        Api today = get("/api/dashboard/report?range=today", mgr);
        assertEquals(0, today.code(), "看板(today)应成功: " + today);
        assertEquals("2099-03-31", today.data().path("startText").asText(),
                "零点前「今日」区间起点 = 3/31: " + today);
        assertEquals("2099-03-31", today.data().path("endText").asText(),
                "零点前区间末端 = 3/31（不是明天）: " + today);

        Api week = get("/api/dashboard/report?range=7d", mgr);
        assertEquals("2099-03-25", week.data().path("startText").asText(),
                "零点前「近7天」起点 = 3/31 − 6 天: " + week);

        // ---- 拨过零点：2099-04-01 00:00:30（同一个上下文、同一个 Bean 实例）----
        CLOCK.set(AFTER_MIDNIGHT);

        Api todayAfter = get("/api/dashboard/report?range=today", mgr);
        assertEquals(0, todayAfter.code(), "看板(today)应成功: " + todayAfter);
        assertEquals("2099-04-01", todayAfter.data().path("startText").asText(),
                "跨零点后「今日」必须变成 4/1 —— 若仍是 3/31，说明这条路径又直连了 LocalDate.now(): " + todayAfter);
        assertEquals("2099-04-01", todayAfter.data().path("endText").asText(),
                "跨零点后区间末端 = 4/1: " + todayAfter);

        Api weekAfter = get("/api/dashboard/report?range=7d", mgr);
        assertEquals("2099-03-26", weekAfter.data().path("startText").asText(),
                "跨零点后「近7天」起点同样后移一天: " + weekAfter);
    }

    @Test
    @DisplayName("现金单账期锚点跟着注入时钟走：3/31 下单 → 4/30 到期；拨到 4/1 → 5/30")
    void receivableDueDateAnchorFollowsInjectedClock() {
        long station = createStation("时钟账期站");
        long manager = createStaff("时钟账期站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("时钟账期客户", "clock-ar-openid");
        long address = createAddress(customer, "时钟小区 1 号");
        long product = createProduct("时钟水", 1, "20.00", "30.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        createCustomerStationConfig(customer, station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        assertEquals(0, put("/api/manager/customers/" + customer + "/credit-terms", mgr,
                "{\"dueDays\":30}").code(), "站长设「月结 30 天」账期");

        // 零点前下单：锚点 = 3/31（3 月最后一天）→ 到期日 3/31 + 30 = 4/30
        assertEquals(0, order(cus, station, address, product, 2, "clock-ar-before").code(),
                "零点前下现金单");
        assertEquals("2099-04-30", dueDateOf("clock-ar-before"),
                "锚点 = 下单日所在月的最后一天（3/31），+30 天 = 4/30");

        // 拨过零点再下一单：锚点 = 4 月最后一天（4/30）→ 到期日 5/30。
        // 账期算法一个字没改（仍是"当月最后一天 + N 天"），变的只是时间源。
        CLOCK.set(AFTER_MIDNIGHT);
        assertEquals(0, order(cus, station, address, product, 2, "clock-ar-after").code(),
                "跨零点后再下现金单");
        assertEquals("2099-05-30", dueDateOf("clock-ar-after"),
                "跨零点后锚点变成 4/30，+30 天 = 5/30 —— 到期日必须跟着注入时钟走");
    }

    /* ==================== 用例内小工具 ==================== */

    /** 现金(2) 下单：账期规则只对货到付款成立（见 ReceivableService.resolveDueDate）。 */
    private Api order(String customerToken, long station, long address, long product, int qty, String key) {
        return post("/api/orders/create", customerToken,
                "{\"addressId\":" + address + ",\"stationId\":" + station
                        + ",\"paymentMethod\":2,\"idempotencyKey\":\"" + key + "\","
                        + "\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty + "}]}");
    }

    /** 按幂等键取该单的应付日期（{@code DATE_FORMAT} 成字符串比 {@code LocalDate} 更好断言）。 */
    private String dueDateOf(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT DATE_FORMAT(due_date, '%Y-%m-%d') FROM orders WHERE idempotency_key=?",
                String.class, idempotencyKey);
    }

    /**
     * 可拨动时钟 —— {@code LocalDate.now(clock)} / {@code LocalDateTime.now(clock)} 只读
     * {@link #instant()} 与 {@link #getZone()}，所以这两件就够了。
     */
    static final class MutableClock extends Clock {

        private final ZoneId zone;
        private volatile Instant instant;

        MutableClock(ZoneId zone, Instant instant) {
            this.zone = zone;
            this.instant = instant;
        }

        void set(Instant next) {
            this.instant = next;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId next) {
            return new MutableClock(next, instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
