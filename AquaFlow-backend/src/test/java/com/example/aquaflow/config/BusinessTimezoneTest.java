package com.example.aquaflow.config;

import com.example.aquaflow.service.ReconciliationService;
import com.example.aquaflow.service.UnpaidWechatOrderSweeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/** The host timezone must not decide China's business day or cron day boundary. */
@ResourceLock("JVM_DEFAULT_TIME_ZONE")
class BusinessTimezoneTest {
    @ParameterizedTest @ValueSource(strings = {"UTC", "America/Los_Angeles", "Pacific/Honolulu"})
    void defaultBusinessDayIsShanghaiEvenOnForeignHosts(String hostZone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(hostZone));
            for (String configured : new String[]{null, "", "  "}) {
                Clock clock = new ClockConfig().clock(configured);
                assertEquals(ZoneId.of("Asia/Shanghai"), clock.getZone());
                assertEquals(LocalDate.of(2026, 10, 5), Instant.parse("2026-10-04T16:30:00Z").atZone(clock.getZone()).toLocalDate());
            }
        } finally { TimeZone.setDefault(original); }
    }

    @Test void explicitZoneStillWorksAndInvalidZoneFails() {
        assertEquals(ZoneId.of("Asia/Shanghai"), new ClockConfig().clock("Asia/Shanghai").getZone());
        assertThrows(java.time.DateTimeException.class, () -> new ClockConfig().clock("not-a-zone"));
    }

    @ParameterizedTest @ValueSource(strings = {"reconcile", "sweep"})
    void bothCronTasksResolveTheirZoneFromTheSameBusinessClock(String task) throws Exception {
        Scheduled scheduled = task.equals("reconcile")
                ? ReconciliationService.class.getMethod("dailyReconcile").getAnnotation(Scheduled.class)
                : UnpaidWechatOrderSweeper.class.getMethod("sweep").getAnnotation(Scheduled.class);
        assertFalse(scheduled.zone().isBlank(), "an empty zone silently follows the scheduler/JVM host");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            Clock clock = new ClockConfig().clock("");
            context.registerBean("clock", Clock.class, () -> clock); context.refresh();
            var evaluation = new StandardEvaluationContext();
            evaluation.setBeanResolver(new BeanFactoryResolver(context));
            String expression = scheduled.zone();
            assertTrue(expression.startsWith("#{") && expression.endsWith("}"));
            String resolved = new SpelExpressionParser().parseExpression(expression.substring(2, expression.length() - 1))
                    .getValue(evaluation, String.class);
            assertEquals(clock.getZone().getId(), resolved);
            ZonedDateTime after = Instant.parse("2026-10-05T00:30:00Z").atZone(ZoneId.of(resolved));
            ZonedDateTime next = CronExpression.parse(scheduled.cron()).next(after);
            assertNotNull(next);
            assertEquals(task.equals("reconcile") ? Instant.parse("2026-10-05T19:00:00Z") : Instant.parse("2026-10-05T00:35:00Z"), next.toInstant());
        }
    }
}
