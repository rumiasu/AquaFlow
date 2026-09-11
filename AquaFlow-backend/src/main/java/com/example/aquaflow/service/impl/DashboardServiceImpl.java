package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.mapper.DashboardMapper;
import com.example.aquaflow.service.DashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长数据看板报表实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>环比自动对齐</b>：查 today 就比昨天，查 7d 就比上一个 7 天 —— 同长度周期才有可比性。</li>
 *   <li><b>趋势补零</b>：没有订单的日期也要补 0，否则折线图的横轴会"跳日"，视觉上误判趋势。</li>
 *   <li><b>文案与涨跌方向由后端派生</b>（deltaText / deltaDir），前端只渲染，避免各端各写一套判断。</li>
 *   <li>所有数值在服务端做除零保护，前端不会拿到 NaN/Infinity。</li>
 * </ul>
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter DAY_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int TOP_N = 5;

    @Autowired
    private DashboardMapper dashboardMapper;

    @Override
    public Map<String, Object> report(Long stationId, String range) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();

        LocalDateTime start;
        LocalDateTime end;
        LocalDateTime prevStart;
        String label;
        String rangeKey;

        switch (range == null ? "" : range) {
            case "today":
                start = todayStart;
                end = todayStart.plusDays(1);
                prevStart = start.minusDays(1);
                label = "今日";
                rangeKey = "today";
                break;
            case "30d":
                start = todayStart.minusDays(29);
                end = todayStart.plusDays(1);
                prevStart = start.minusDays(30);
                label = "近30天";
                rangeKey = "30d";
                break;
            case "7d":
            default:
                start = todayStart.minusDays(6);
                end = todayStart.plusDays(1);
                prevStart = start.minusDays(7);
                label = "近7天";
                rangeKey = "7d";
                break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", rangeKey);
        result.put("label", label);
        result.put("startText", start.format(DAY_FULL));
        result.put("endText", now.format(DAY_FULL));

        // ==================== 汇总 + 环比 ====================
        Map<String, Object> cur = dashboardMapper.summary(stationId, start, end);
        Map<String, Object> prev = dashboardMapper.summary(stationId, prevStart, start);
        int newCustomers = dashboardMapper.countNewCustomers(stationId, start, end);
        int prevNewCustomers = dashboardMapper.countNewCustomers(stationId, prevStart, start);

        Map<String, Object> barrelCur = toBarrelFlow(dashboardMapper.barrelFlow(stationId, start, end));
        Map<String, Object> barrelPrev = toBarrelFlow(dashboardMapper.barrelFlow(stationId, prevStart, start));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderCount", num(cur.get("orderCount")));
        summary.put("completedOrders", num(cur.get("completedOrders")));
        summary.put("cancelledOrders", num(cur.get("cancelledOrders")));
        summary.put("grossAmount", dec(cur.get("grossAmount")));
        summary.put("paidAmount", dec(cur.get("paidAmount")));
        summary.put("pendingAmount", dec(cur.get("pendingAmount")));
        summary.put("activeCustomers", num(cur.get("activeCustomers")));
        summary.put("newCustomers", newCustomers);
        summary.put("barrelsIn", barrelCur.get("in"));
        summary.put("barrelsOut", barrelCur.get("out"));
        // 客单价 = 营业额 / 非取消订单数（服务端算好，前端不做除法）
        summary.put("avgOrderAmount", divide(dec(cur.get("grossAmount")), num(cur.get("orderCount")) - num(cur.get("cancelledOrders"))));
        // 完成率 = 已完成 / 总单
        summary.put("completeRate", rate(num(cur.get("completedOrders")), num(cur.get("orderCount"))));
        // 取消率
        summary.put("cancelRate", rate(num(cur.get("cancelledOrders")), num(cur.get("orderCount"))));
        result.put("summary", summary);

        // 环比对比表：key -> {current, previous, deltaText, deltaDir}
        Map<String, Map<String, Object>> compare = new LinkedHashMap<>();
        compare.put("orderCount", deltaCount(num(cur.get("orderCount")), num(prev.get("orderCount")), "单", false));
        // 金额上涨是好事，不算 inverted；金额不乘 100 缩放，直接原值返回
        compare.put("grossAmount", deltaAmount(dec(cur.get("grossAmount")), dec(prev.get("grossAmount"))));
        compare.put("paidAmount", deltaAmount(dec(cur.get("paidAmount")), dec(prev.get("paidAmount"))));
        compare.put("newCustomers", deltaCount(newCustomers, prevNewCustomers, "人", false));
        compare.put("barrelsIn", deltaCount(num(barrelCur.get("in")), num(barrelPrev.get("in")), "个", false));
        // 取消率上升是坏事 → inverted=true
        compare.put("cancelledOrders", deltaCount(num(cur.get("cancelledOrders")), num(prev.get("cancelledOrders")), "单", true));
        result.put("compare", compare);

        // ==================== 按天趋势（补零，保证横轴连续） ====================
        List<Map<String, Object>> trend = fillTrend(
                dashboardMapper.dailyTrend(stationId, start, end), start.toLocalDate(), end.toLocalDate());
        result.put("trend", trend);
        result.put("trendMax", trend.stream().mapToInt(t -> (int) num(t.get("orders"))).max().orElse(0));
        result.put("trendAmountMax", trend.stream().map(t -> dec(t.get("amount")))
                .map(BigDecimal::doubleValue).max(Double::compare).orElse(0d));

        // ==================== 各维度分布 ====================
        result.put("statusDistribution", decorateStatus(dashboardMapper.statusDistribution(stationId, start, end)));
        result.put("payMethodDistribution", decoratePayMethod(dashboardMapper.payMethodDistribution(stationId, start, end)));

        List<Map<String, Object>> products = dashboardMapper.topProducts(stationId, start, end, TOP_N);
        long productQtyTotal = products.stream().mapToLong(p -> num(p.get("qty"))).sum();
        for (Map<String, Object> p : products) {
            long qty = num(p.get("qty"));
            p.put("share", productQtyTotal > 0 ? qty * 100 / productQtyTotal : 0);
            p.put("revenueText", dec(p.get("revenue")).toPlainString());
        }
        result.put("topProducts", products);

        List<Map<String, Object>> customers = dashboardMapper.topCustomers(stationId, start, end, TOP_N);
        for (Map<String, Object> c : customers) {
            c.put("amountText", dec(c.get("amount")).toPlainString());
        }
        result.put("topCustomers", customers);

        List<Map<String, Object>> staff = dashboardMapper.staffPerformance(stationId, start, end);
        for (Map<String, Object> s : staff) {
            long total = num(s.get("totalOrders"));
            s.put("amountText", dec(s.get("amount")).toPlainString());
            s.put("completeRate", rate(num(s.get("completedOrders")), total));
        }
        result.put("staffPerformance", staff);

        // 时段分布补齐 0-23 点
        result.put("hourDistribution", fillHours(dashboardMapper.hourDistribution(stationId, start, end)));

        // 欠桶提醒（瞬时值，与时间段无关）
        result.put("owedCustomers", dashboardMapper.owedCustomers(stationId, TOP_N));

        // 待收款式提醒：待收款金额 > 0 时前端高亮
        result.put("hasPendingAmount", dec(cur.get("pendingAmount")).compareTo(BigDecimal.ZERO) > 0);
        return result;
    }

    // ==================== 私有工具 ====================

    /** 数量型环比：方向与"是好是坏"由后端统一判定（inverted=true 表示上涨是坏事，如取消单数） */
    private Map<String, Object> deltaCount(long cur, long prev, String unit, boolean inverted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", cur);
        m.put("previous", prev);
        m.put("unit", unit);
        if (prev == 0) {
            boolean up = cur > 0;
            m.put("deltaText", up ? "新增" : "持平");
            m.put("deltaDir", up ? "up" : "flat");
            m.put("good", up ? !inverted : null);
            return m;
        }
        long diff = cur - prev;
        if (diff == 0) {
            m.put("deltaPct", 0);
            m.put("deltaText", "持平");
            m.put("deltaDir", "flat");
            m.put("good", null);
            return m;
        }
        long pct = Math.round(Math.abs(diff) * 100.0 / prev);
        m.put("deltaPct", pct);
        m.put("deltaText", (diff > 0 ? "+" : "-") + pct + "%");
        m.put("deltaDir", diff > 0 ? "up" : "down");
        m.put("good", diff > 0 != inverted);
        return m;
    }

    /**
     * 金额型环比：current/previous 原值返回（不做任何缩放），只派生涨跌方向。
     * 金额上涨一律视为"好"。
     */
    private Map<String, Object> deltaAmount(BigDecimal cur, BigDecimal prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", cur);
        m.put("previous", prev);
        m.put("unit", "元");
        int cmp = cur.compareTo(prev);
        if (cmp == 0) {
            m.put("deltaText", "持平");
            m.put("deltaDir", "flat");
            m.put("good", null);
            return m;
        }
        if (prev.compareTo(BigDecimal.ZERO) == 0) {
            m.put("deltaText", "新增");
            m.put("deltaDir", "up");
            m.put("good", true);
            return m;
        }
        long pct = cur.subtract(prev).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(prev, 0, RoundingMode.HALF_UP).longValue();
        m.put("deltaPct", pct);
        m.put("deltaText", (cmp > 0 ? "+" : "-") + pct + "%");
        m.put("deltaDir", cmp > 0 ? "up" : "down");
        m.put("good", cmp > 0);
        return m;
    }

    /** 趋势补零：缺数据的日期补 0，让折线横轴连续 */
    private List<Map<String, Object>> fillTrend(List<Map<String, Object>> rows, LocalDate start, LocalDate end) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                Object dt = r.get("dt");
                byDate.put(dt == null ? "" : String.valueOf(dt).substring(0, 10), r);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end.minusDays(1)); d = d.plusDays(1)) {
            String key = d.format(DAY_FULL);
            Map<String, Object> row = byDate.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", key);
            m.put("label", d.format(DAY));
            m.put("orders", row != null ? num(row.get("cnt")) : 0);
            m.put("amount", row != null ? dec(row.get("amount")) : BigDecimal.ZERO);
            out.add(m);
        }
        return out;
    }

    /** 时段分布补齐 0-23 点 */
    private List<Map<String, Object>> fillHours(List<Map<String, Object>> rows) {
        Map<Integer, Long> byHour = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                byHour.put(((Number) r.get("hr")).intValue(), num(r.get("cnt")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("label", String.format("%02d", h));
            m.put("orders", byHour.getOrDefault(h, 0L));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> decorateStatus(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        long total = rows.stream().mapToLong(r -> num(r.get("cnt"))).sum();
        for (Map<String, Object> r : rows) {
            int status = (int) num(r.get("status"));
            long cnt = num(r.get("cnt"));
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("statusText", OrderStatus.textOf(status));
            m.put("share", total > 0 ? Math.round(cnt * 100.0 / total) : 0);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> decoratePayMethod(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        long total = rows.stream().mapToLong(r -> num(r.get("cnt"))).sum();
        for (Map<String, Object> r : rows) {
            Object method = r.get("method");
            int m = method == null ? -1 : ((Number) method).intValue();
            long cnt = num(r.get("cnt"));
            Map<String, Object> x = new LinkedHashMap<>(r);
            x.put("methodText", m >= 0 ? PayMethod.textOf(m) : "其他");
            x.put("amountText", dec(r.get("amount")).toPlainString());
            x.put("share", total > 0 ? Math.round(cnt * 100.0 / total) : 0);
            out.add(x);
        }
        return out;
    }

    private Map<String, Object> toBarrelFlow(List<Map<String, Object>> rows) {
        long in = 0;
        long out = 0;
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                int type = (int) num(r.get("type"));
                long qty = num(r.get("qty"));
                if (type == 1) in = qty;
                else if (type == 2) out = qty;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("in", in);
        m.put("out", out);
        return m;
    }

    private long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (v instanceof BigDecimal) return ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal divide(BigDecimal a, long b) {
        if (b <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return a.divide(BigDecimal.valueOf(b), 2, RoundingMode.HALF_UP);
    }

    /** 百分比（0-100）四舍五入，除零安全。11.76% 应显示 12% 而不是截断成 11% */
    private long rate(long part, long total) {
        if (total <= 0) return 0;
        return Math.round(part * 100.0 / total);
    }
}
