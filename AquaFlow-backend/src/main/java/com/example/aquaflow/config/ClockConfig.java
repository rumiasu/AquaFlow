package com.example.aquaflow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时钟（F-16）。已接入业务时间消费者的统一来源。
 *
 * <p><b>为什么要有这个 Bean</b>：生产代码直连 {@code LocalDate.now()} / {@code LocalDateTime.now()}
 * 读的是 <b>JVM 默认时区 + 真实时间</b>，谁也没法在测试里把它钉住。后果已经出现过：
 * 报表类用例只能自己拿 {@code LocalDate.now()} 算区间端点，而"算端点"与"造数"之间
 * 只要跨过一次零点，端点就落到前一天、数据落到后一天，用例<b>偶发变红且无法复现</b>
 * （23:59 造数、00:00 才算端点）；另一种形态是"今天/本月"这类口径在测试里根本不可断言。
 * 注入 {@link Clock} 之后，测试换掉这一个 Bean 就能把"现在"冻结/拨动
 * （示例见 {@code integration/BusinessClockIntegrationTest}）。</p>
 *
 * <p><b>时区为什么必须显式想清楚</b>：本仓面向中国水站，业务日界是 {@code Asia/Shanghai}。
 * 默认显式使用上海时区，不再跟随部署机器的 JVM 默认时区；
 * {@code app.time-zone} 可显式覆盖，空值同样回退到上海。cron 任务读取这个 Bean 的时区，
 * 避免日结时间与业务日界分离。
 * ⚠️ <b>不要</b>改成 {@code Clock.systemUTC()}：那会让中国水站的"今天"从 08:00 才算起，
 * 凌晨 0-8 点的单会被算进前一天。</p>
 *
 * <p>⚠️ 本 Bean 只管 Java 侧取时间。<b>SQL 里的 {@code NOW()} / {@code CURDATE()} 不归它管</b>
 * （那是库的时间，改它要连 MySQL 会话时区一起动），别为了"统一"去改 SQL。</p>
 */
@Slf4j
@Configuration
public class ClockConfig {

    /**
     * @param configuredZone {@code app.time-zone}（IANA 时区名，如 {@code Asia/Shanghai}）；
     *                       留空 = Asia/Shanghai
     */
    @Bean
    public Clock clock(@Value("${app.time-zone:Asia/Shanghai}") String configuredZone) {
        ZoneId zone = (configuredZone == null || configuredZone.isBlank())
                ? ZoneId.of("Asia/Shanghai")
                : ZoneId.of(configuredZone);
        log.info("[F-16] 业务时钟时区 = {}（app.time-zone 可显式指定；默认 Asia/Shanghai）", zone);
        return Clock.system(zone);
    }
}
