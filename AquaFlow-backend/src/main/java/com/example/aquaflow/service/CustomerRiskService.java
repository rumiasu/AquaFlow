package com.example.aquaflow.service;

import com.example.aquaflow.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户信用风险（验资）—— 2026-09-21 新增，**只读、不落库**。
 *
 * <h3>为什么是"算出来的"而不是"存出来的"</h3>
 * <p>风险等级与额度全部由现有数据现算，<b>不新增状态列、不需要手动解冻</b> ——
 * 欠款一结清，下一次查询自然回到「正常」。手写一个"冻结/解冻"开关看着直观，
 * 但它会变成<b>站长又要记得去点一下</b>的东西（忘了解冻 → 客户莫名其妙下不了单；
 * 忘了冻结 → 风险裸奔），而站长主要是力工，不该被系统变成负担。</p>
 *
 * <h3>四个等级（判据都在 {@link #assess} 里）</h3>
 * <ul>
 *   <li>{@code NORMAL} 正常：没有未结赊账</li>
 *   <li>{@code WATCH} 关注：有挂账但都在账期内 —— <b>这是正常经营</b>（月结客户天天如此），只做展示</li>
 *   <li>{@code ALERT} 预警：有<b>已逾期</b>的未结赊账，或挂账已超额度</li>
 *   <li>{@code FREEZE} 冻结：最长逾期超过阈值（默认 15 天）—— 此时才拦"往外掏钱"的动作（退押金）</li>
 * </ul>
 *
 * <h3>额度怎么来（不给死值）</h3>
 * <p>{@code 可赊额度 = max(下限, 该客户近 90 天月均水费 × 3)}。
 * 例：每月买 200 元的水 → 额度约 600；每月买 2000 → 约 6000。</p>
 * <p><b>为什么跟着客户自己的消费走</b>：写死一个数（例如 5000）对小水站太重、
 * 对大客户又太轻；跟着客户的历史消费走，则"小客户天然拿不到大额度、大客户才拿得到"，
 * 而且<b>站长一个数都不用填</b>。新客户没有历史 → 用平台下限兜底（先做小生意、攒出信用再涨）。</p>
 *
 * <p>⚠️ 只算 {@code payment_method = 2}（现金/赊账）：微信未付单与水票未付单不是赊账，
 * 它们收不到钱根本不会进配送流程，算进来会把客户可用额度凭空吃掉。</p>
 */
@Service
@Slf4j
public class CustomerRiskService {

    /** 正常：没有未结赊账 */
    public static final String NORMAL = "NORMAL";
    /** 关注：有挂账但都在账期内（正常经营，只展示） */
    public static final String WATCH = "WATCH";
    /** 预警：有已逾期的未结赊账，或挂账超额度 */
    public static final String ALERT = "ALERT";
    /** 冻结：最长逾期超过阈值 —— 此时拦"往外掏钱"的动作 */
    public static final String FREEZE = "FREEZE";

    /** 验资窗口（天）：用它算"这个客户平时一个月买多少水"。 */
    private static final int LOOKBACK_DAYS = 90;

    /** 额度 = 月均水费 × 本倍数。3 个月 ≈ 给一个季度的周转。 */
    private static final int LIMIT_MULTIPLIER = 3;

    /** 新客户（近 90 天没有订单）的下限额度。先做小生意，攒出信用再由额度公式自己涨上来。 */
    private static final BigDecimal MIN_LIMIT = new BigDecimal("300");

    /**
     * 冻结阈值：最长逾期超过这么多天就升级为 {@code FREEZE}。
     * 平台默认 15 天（`app.credit.freeze-overdue-days`），**站级暂不可配** ——
     * 按"每多一个开关，站长就多一份误判风险"的口径，先只给平台默认。
     */
    @Value("${app.credit.freeze-overdue-days:15}")
    private int freezeOverdueDays;

    private final OrderMapper orderMapper;

    public CustomerRiskService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 中文文案（全系统唯一来源，前端禁止自带映射表）。 */
    public static String textOf(String level) {
        if (FREEZE.equals(level)) return "冻结";
        if (ALERT.equals(level)) return "预警";
        if (WATCH.equals(level)) return "关注";
        return "正常";
    }

    /**
     * 四个等级的<b>唯一判据</b> —— {@link #assess}（客户详情）与 {@link #summarizeStation}
     * （订单列表上色）都走这里。
     *
     * <p>⚠️ <b>2026-09-22 收敛前的分叉</b>：列表那条路原来看不出"超额度"（它不查额度），
     * 于是同一个客户在列表上是黄（关注）、点进详情却变红（预警）。<b>判据必须只有一份，
     * 列表与详情不许各写一套</b>（本仓"计价双轨"的同款形状）。</p>
     *
     * @param limit 可赊额度；{@code null} 或 ≤0 视为"不设额度"（只按逾期判）
     */
    private String levelOf(int overdueCount, int maxOverdueDays, BigDecimal outstanding, BigDecimal limit) {
        if (overdueCount > 0) {
            return maxOverdueDays > freezeOverdueDays ? FREEZE : ALERT;
        }
        if (limit != null && limit.signum() > 0 && outstanding.compareTo(limit) > 0) {
            return ALERT;
        }
        return outstanding.signum() > 0 ? WATCH : NORMAL;
    }

    /** 风险原因文案（给用户看的那句话）—— 与 {@link #levelOf} 配对，等级只有一个来源。 */
    private String reasonOf(String level, int overdueCount, int maxOverdueDays,
                            BigDecimal outstanding, BigDecimal limit) {
        if (FREEZE.equals(level)) {
            return "有 " + overdueCount + " 笔逾期未结（最长 " + maxOverdueDays + " 天，超过 "
                    + freezeOverdueDays + " 天）：已暂停退押金，请先结清欠款";
        }
        if (ALERT.equals(level)) {
            return overdueCount > 0
                    ? "有 " + overdueCount + " 笔逾期未结（最长 " + maxOverdueDays + " 天）：不再给新的赊账单"
                    : "未结赊账 ¥" + outstanding.toPlainString() + " 已超过可赊额度 ¥"
                      + (limit == null ? "0" : limit.toPlainString()) + "：不再给新的赊账单";
        }
        if (WATCH.equals(level)) {
            return "有 ¥" + outstanding.toPlainString() + " 挂账，都在账期内";
        }
        return "没有未结欠款";
    }

    /**
     * 算该客户在**该站**的信用画像。只读、幂等，可以随时调。
     *
     * <p>返回键：{@code level} / {@code levelText} / {@code creditLimit}（可赊额度）/
     * {@code outstandingCredit}（已用额度）/ {@code limitExceeded} /
     * {@code overdueAmount} / {@code overdueCount} / {@code maxOverdueDays} /
     * {@code returnBlocked}（退押金是否会被拦）/ {@code reason}（给用户看的文案）。</p>
     */
    public Map<String, Object> assess(Long customerId, Long stationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customerId);
        out.put("stationId", stationId);
        if (customerId == null || stationId == null) {
            out.put("level", NORMAL);
            out.put("levelText", textOf(NORMAL));
            out.put("reason", "无法识别客户或水站");
            return out;
        }

        BigDecimal outstanding = nz(orderMapper.sumOutstandingCredit(customerId, stationId));
        BigDecimal overdue = nz(orderMapper.sumOverdueCashAmount(customerId, stationId));
        int overdueCount = orderMapper.countOverdueCashOrders(customerId, stationId);
        int maxOverdueDays = orderMapper.maxOverdueDaysForCredit(customerId, stationId);
        BigDecimal limit = creditLimit(customerId, stationId);
        boolean limitExceeded = limit.signum() > 0 && outstanding.compareTo(limit) > 0;

        String level = levelOf(overdueCount, maxOverdueDays, outstanding, limit);
        String reason = reasonOf(level, overdueCount, maxOverdueDays, outstanding, limit);

        out.put("level", level);
        out.put("levelText", textOf(level));
        out.put("creditLimit", limit);
        out.put("outstandingCredit", outstanding);
        out.put("limitExceeded", limitExceeded);
        out.put("overdueAmount", overdue);
        out.put("overdueCount", overdueCount);
        out.put("maxOverdueDays", maxOverdueDays);
        // 只有"预警"及以上才拦退押金 —— WATCH 是正常经营（月结客户天天如此），拦它会误伤主业
        out.put("returnBlocked", ALERT.equals(level) || FREEZE.equals(level));
        out.put("reason", reason);
        return out;
    }

    /**
     * 可赊额度：{@code max(下限, 近 90 天月均水费 × 3)}。
     *
     * <p>[2026-09-21] 用户明确否掉了写死一个数：「5000 对于小站来说挺沉重的」。
     * 于是额度跟着**这个客户自己的**消费规模走 —— 站长一个数都不用填。</p>
     */
    public BigDecimal creditLimit(Long customerId, Long stationId) {
        BigDecimal water90 = nz(orderMapper.sumWaterAmountSince(customerId, stationId, LOOKBACK_DAYS));
        return limitOf(water90);
    }

    /**
     * 额度公式本体（{@code max(下限, 近 90 天水费月均 × 3)}）—— <b>纯函数</b>，
     * 供 {@link #creditLimit}（单查）与 {@link #summarizeStation}（批量，已在同一次查询里
     * 拿到本站每个客户的 90 天窗口水量）共用。抽出来是为了让"列表上色"也用上额度，
     * 而不必为每一行再查一次库（N+1）。
     */
    static BigDecimal limitOf(BigDecimal water90) {
        BigDecimal amount = nz(water90);
        // ⚠️ 先按 4 位小数算月均、最后才四舍五入到分：若一开始就舍到 2 位，
        // `400 / 3 = 133.33` 再 ×3 得 `399.99`，于是"刚好把额度用满"的客户会被判成超额度
        // （实测踩过：挂账 400.00 > 额度 399.99）。舍入误差不该变成一次误判。
        BigDecimal monthlyAvg = amount.divide(BigDecimal.valueOf(LOOKBACK_DAYS / 30.0), 4, RoundingMode.HALF_UP);
        BigDecimal byHistory = monthlyAvg.multiply(BigDecimal.valueOf(LIMIT_MULTIPLIER)).setScale(2, RoundingMode.HALF_UP);
        return byHistory.compareTo(MIN_LIMIT) > 0 ? byHistory : MIN_LIMIT;
    }

    /** 轻量版：只取等级（退桶拦截用，避免为此多查几次库）。 */
    public String levelOf(Long customerId, Long stationId) {
        return String.valueOf(assess(customerId, stationId).get("level"));
    }

    /**
     * 本站**有赊账的客户**批量摘要（{@code customerId → 摘要}）—— 订单列表上色用。
     *
     * <p>一次查完再在内存里按 customerId 匹配，<b>不要逐行调 {@link #assess}</b>：
     * 列表可能有几十行，逐行算 = 几十次 SQL（本仓"列表页 N+1"的老坑）。</p>
     *
     * <p>⚠️ 判据与 {@link #assess} <b>同源</b>（同一个 {@link #levelOf}、同一个 {@link #limitOf}）：
     * 列表上色与详情页的风险等级在**同一批数据**上永远不会打架。
     * 2026-09-22 之前这里**不查额度**，于是"超额度但没逾期"的客户在列表上是黄、点进去是红。</p>
     */
    public Map<Long, Map<String, Object>> summarizeStation(Long stationId) {
        Map<Long, Map<String, Object>> out = new java.util.HashMap<>();
        if (stationId == null) {
            return out;
        }
        // 第二次查询：本站每个客户近 90 天的水量（算可赊额度用）。
        // ⚠️ 为什么不能在下面那条 SQL 里顺手 sum 出来：那条只看"未结赊账"
        // （payment_status = 1 且 payment_method = 2），而额度公式的分子是**全部**非取消订单的水费
        // （与 sumWaterAmountSince 逐字同源，不过滤支付方式/状态）。塞进同一条 group by 会把额度算小，
        // 于是"刚好在额度内"的客户被误判成超额度 —— 与 2026-09-21 那次舍入误判同形。
        Map<Long, BigDecimal> water90 = new java.util.HashMap<>();
        for (Map<String, Object> r : orderMapper.waterAmountSinceByStation(stationId, LOOKBACK_DAYS)) {
            Object cid = r.get("customerId");
            if (cid != null) {
                water90.put(((Number) cid).longValue(), nz(asDecimal(r.get("water90"))));
            }
        }
        for (Map<String, Object> r : orderMapper.creditSummaryByStation(stationId)) {
            Object cid = r.get("customerId");
            if (cid == null) {
                continue;
            }
            long customerId = ((Number) cid).longValue();
            int maxOverdue = asInt(r.get("maxOverdueDays"));
            int overdueCount = asInt(r.get("overdueCount"));
            BigDecimal outstanding = nz(asDecimal(r.get("outstandingCredit")));
            BigDecimal limit = limitOf(water90.get(customerId));
            String level = levelOf(overdueCount, maxOverdue, outstanding, limit);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("level", level);
            one.put("levelText", textOf(level));
            one.put("creditLimit", limit);
            one.put("outstandingCredit", outstanding);
            one.put("overdueDays", maxOverdue);
            // 给用户看的那句话也一并下发：前端**不自己拼**「欠了多少 / 逾期几天」，
            // 否则同一个客户在列表上与在详情页上会是两句不一样的话。
            one.put("note", reasonOf(level, overdueCount, maxOverdue, outstanding, limit));
            out.put(customerId, one);
        }
        return out;
    }

    private static int asInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static java.math.BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal bd) return bd;
        return new java.math.BigDecimal(String.valueOf(v));
    }

    /**
     * 退押金被拦时给用户看的原因 —— **全系统唯一来源**。
     *
     * <p>不说"押金余额不足"：那句话在"押金穿底"的情形下虽然也成立，
     * 但真实原因是<b>客户还欠着钱</b>，只报余额不足会让站长与客户都看不出该怎么办。</p>
     */
    public String returnBlockedReason(Long customerId, Long stationId) {
        Map<String, Object> a = assess(customerId, stationId);
        return "该客户在本站有未结欠款（" + a.get("reason") + "），暂不能退桶退押金，请先结清";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
