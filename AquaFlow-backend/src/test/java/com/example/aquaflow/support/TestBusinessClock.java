package com.example.aquaflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 测试用的**可冻结业务时钟**（F-43 / F-44）。
 *
 * <p><b>它解决什么</b>：生产侧「取现在」已经统一走 {@code config/ClockConfig} 的 {@link Clock}
 * Bean（F-16）；但用例侧仍有两套时钟 —— Java 的 {@code LocalDate.now()} 与 SQL 的
 * {@code CURDATE()} / {@code NOW()}。只要两者不一致（跨零点、或把 Java 时钟冻结到别的年份），
 * 区间端点与数据就会落在不同的日子上，表现为**偶发红且无法复现**，或者"冻结了 Java 时钟反而
 * 换个姿势红"（Java 说 2099、SQL 说今天）。</p>
 *
 * <p><b>用法</b>（两步，缺一不可）：</p>
 * <pre>
 * &#64;Import(TestBusinessClock.Config.class)          // ① 顶掉 ClockConfig 的默认时钟
 * class XxxIntegrationTest extends AbstractIntegrationTest {
 *     &#64;BeforeEach void freezeClock() {
 *         TestBusinessClock.freezeAtDbNow(jdbc);    // ② 把业务"今天"钉在**数据库当前时刻**
 *     }
 * }
 * </pre>
 *
 * <p>第 ② 步为什么默认钉在**库的时刻**而不是拍一个 2099：生产 SQL 里仍有一批
 * {@code CURDATE()} / {@code NOW()}（逾期判据、对账 run_date、时间戳列），它们读的是**库的时间**，
 * 不受本时钟影响。把 Java 侧钉在与库同一天，两侧才等价；要测"时间被拨动"的场景，
 * 请显式 {@link #freezeAt(LocalDateTime)} 到目标时刻，并把**断言里的 SQL 日期函数换成同一个
 * 冻结常量的入参**（本仓 F-43 的判据：只冻结 Java 侧 = 换个姿势红）。</p>
 *
 * <p>⚠️ {@code @Primary} 是必须的：{@code ClockConfig} 也提供一个 {@code Clock} Bean，
 * 注入点（{@code util/BusinessTime}）按 primary 取值。⚠️ 已有的
 * {@code BusinessClockIntegrationTest} 自带一份自己的可拨动时钟（它要验的就是"能拨"），
 * 别改它、也别给它再 {@code @Import} 本类（两个 primary 会歧义）。</p>
 */
public final class TestBusinessClock {

    /** 与生产同源：跟随 JVM 默认时区（{@code ClockConfig} 的默认值就是这么取的）。 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 整个测试 JVM 共用这一个可拨动时钟 —— 生产代码注入的就是它。 */
    private static final MutableClock CLOCK = new MutableClock(ZONE, Instant.now());

    private TestBusinessClock() {
    }

    /** 把业务"现在"钉到指定时刻（按 JVM 默认时区解释）。 */
    public static void freezeAt(LocalDateTime moment) {
        CLOCK.set(moment.atZone(ZONE).toInstant());
    }

    /** 把业务"今天"钉到指定日期（当天 12:00 —— 离两侧零点都足够远）。 */
    public static void freezeAt(LocalDate day) {
        freezeAt(day.atTime(12, 0));
    }

    /**
     * 把业务"现在"钉到**数据库当前时刻**：Java 侧与 SQL 侧从此同源。
     *
     * <p>这是"只想让用例确定下来、不想改口径"的默认做法 —— 与 F-16 改造前的行为逐字等价，
     * 只是把"取现在"从 JVM 真实时间换成了一个**在整个用例内不再移动**的值。</p>
     */
    public static void freezeAtDbNow(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        LocalDateTime dbNow = jdbc.queryForObject("SELECT NOW()", LocalDateTime.class);
        freezeAt(dbNow);
    }

    /** 当前（冻结后的）业务时刻 —— 用例构造 SQL 参数时用这个，不要再用 {@code LocalDateTime.now()}。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(CLOCK);
    }

    /** 当前（冻结后的）业务"今天"。 */
    public static LocalDate today() {
        return LocalDate.now(CLOCK);
    }

    /**
     * 顶掉 {@code config/ClockConfig} 的默认时钟。
     * <p>用法：在测试类上 {@code @Import(TestBusinessClock.Config.class)}。</p>
     */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        Clock businessClock() {
            return CLOCK;
        }
    }

    /**
     * 可拨动时钟 —— {@code LocalDate.now(clock)} / {@code LocalDateTime.now(clock)} 只读
     * {@link #instant()} 与 {@link #getZone()}，所以这两件就够了。
     * （与 {@code BusinessClockIntegrationTest} 里那份同形；那份是私有实现，不复用以免互相牵制。）
     */
    public static final class MutableClock extends Clock {

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
