package com.example.aquaflow.service;

import com.example.aquaflow.mapper.DashboardMapper;
import com.example.aquaflow.service.impl.DashboardServiceImpl;
import com.example.aquaflow.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed Shanghai clock, observed query windows and zero/negative comparison contracts. */
class DashboardComparisonTest {
    record Window(String query, Long station, LocalDateTime start, LocalDateTime end) {}
    private final List<Window> windows = new ArrayList<>();

    private Map<String, Object> report(String instant, String range, long previousOrders, String currentAmount, String previousAmount) {
        DashboardMapper mapper = (DashboardMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DashboardMapper.class}, (proxy, method, args) -> {
            windows.add(new Window(method.getName(), (Long) args[0],
                    args.length > 1 && args[1] instanceof LocalDateTime d ? d : null,
                    args.length > 2 && args[2] instanceof LocalDateTime d ? d : null));
            if (method.getName().equals("summary")) {
                boolean current = windows.stream().filter(w -> w.query().equals("summary")).count() == 1;
                return Map.of("orderCount", current ? 1L : previousOrders,
                        "grossAmount", new BigDecimal(current ? currentAmount : previousAmount));
            }
            return method.getReturnType() == int.class ? 0 : List.of();
        });
        DashboardServiceImpl service = new DashboardServiceImpl();
        ReflectionTestUtils.setField(service, "dashboardMapper", mapper);
        ReflectionTestUtils.setField(service, "businessTime", new BusinessTime(Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai"))));
        return service.report(73L, range);
    }

    @ParameterizedTest @CsvSource({"2026-11-03T00:27:15Z, 2026-11-03T08:27:15", "2026-11-02T16:00:00Z, 2026-11-03T00:00:00", "2026-11-02T16:00:30Z, 2026-11-03T00:00:30"})
    void todayUsesOneCapturedShanghaiTimeEvenAtMidnight(String instant, String local) {
        Map<String, Object> r = report(instant, "today", 1, "20", "10");
        LocalDateTime now = LocalDateTime.parse(local), start = now.toLocalDate().atStartOfDay();
        for (Window w : windows) {
            assertEquals(73L, w.station());
            if (w.start() == null) continue;
            assertTrue(w.start().equals(start) || w.start().equals(start.minusDays(1)), w.toString());
            assertEquals(w.start().equals(start) ? now : now.minusDays(1), w.end(), w.toString());
        }
        assertTrue(r.get("comparisonNote").toString().contains("昨日同一时刻"));
        assertEquals(1, ((List<?>) r.get("trend")).size(), "midnight must retain today's zero trend point");
        assertEquals(24, ((List<?>) r.get("hourDistribution")).size());
    }

    @ParameterizedTest @CsvSource({"0, 无对比记录", "2, 减少"})
    void zeroAmountDistinguishesMissingOrdersWithoutInventingAPercentage(long previousOrders, String text) {
        Map<String, Object> r = report("2026-11-03T00:27:15Z", "today", previousOrders, "-3", "0");
        Map<?, ?> amount = (Map<?, ?>) ((Map<?, ?>) r.get("compare")).get("grossAmount");
        assertEquals(text, amount.get("deltaText"));
        assertEquals("down", amount.get("deltaDir"));
        assertFalse(amount.containsKey("deltaPct"));
    }

    @Test void negativeBaselineKeepsArithmeticDirectionAndOriginalAmounts() {
        Map<String, Object> r = report("2026-11-03T00:27:15Z", "today", 2, "-5", "-10");
        Map<?, ?> amount = (Map<?, ?>) ((Map<?, ?>) r.get("compare")).get("grossAmount");
        assertEquals("+50%", amount.get("deltaText"));
        assertEquals(new BigDecimal("-5.00"), amount.get("current"));
        assertEquals(new BigDecimal("-10.00"), amount.get("previous"));
        // Short labels still distinguish zero-baseline directions without inventing percentages.
        DashboardServiceImpl service = new DashboardServiceImpl();
        for (String[] sample : new String[][]{{"-3", "减少", "down"}, {"0", "持平", "flat"}, {"3", "新增", "up"}}) {
            Map<?, ?> zero = ReflectionTestUtils.invokeMethod(service, "deltaAmount", new BigDecimal(sample[0]), BigDecimal.ZERO);
            assertNotNull(zero);
            assertEquals(sample[1], zero.get("deltaText"));
            assertEquals(sample[2], zero.get("deltaDir"));
            assertEquals(new BigDecimal(sample[0]), zero.get("current"));
            assertEquals(BigDecimal.ZERO, zero.get("previous"));
            assertFalse(zero.containsKey("deltaPct"));
        }
    }

    @ParameterizedTest @CsvSource({"7d, 7", "30d, 30"})
    void historicalRangesRetainTheirExistingDayWindowsAndSayTodayIsPartial(String range, int days) {
        Map<String, Object> r = report("2026-11-03T00:27:15Z", range, 2, "20", "10");
        Window current = windows.get(0), previous = windows.get(1);
        assertEquals(LocalDateTime.parse("2026-11-04T00:00:00"), current.end());
        assertEquals(current.start().minusDays(days), previous.start());
        assertEquals(current.start(), previous.end());
        assertEquals(days, ((List<?>) r.get("trend")).size());
        assertTrue(r.get("comparisonNote").toString().contains("本期含今日"));
    }
}
