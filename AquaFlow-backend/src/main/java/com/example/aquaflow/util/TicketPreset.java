package com.example.aquaflow.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 统一折扣的**平台预设档**（2026-09-19 立；2026-09-20 随形态收口改用途）—— 纯规则，无 IO。
 *
 * <p><b>为什么要有它</b>：站级「统一折扣」要挂一份"买多少张打几折"的价目表。
 * 让站长从空白开始想"10 张该打几折"是把定价知识推给站长；平台给一组**建议档**（张数 + 折扣），
 * 他点一下填进表单、再按自己的经营情况改数字即可。</p>
 *
 * <p>⚠️ <b>预设只是"一键填入"的草稿，不是落库的数据</b>：档位永远由站长保存
 * （{@code POST /api/ticket-discounts}）。平台<b>不会</b>替他建行 —— 因为
 * "本站配了上架的折扣档"就是**统一折扣是否生效**的判据，自动落行等于替所有水站开通了折扣工具。</p>
 *
 * <p>⚠️ <b>它给的是"折扣"，不是"价格"</b>（产品 2026-09-20：「执行上也不是统一定价，
 * 而是**对应水怎么统一打折**」）：价格必须按**各款水自己的水票价**现算，
 * 所以本类只提供 {@link #discountText} / {@link #title} 两个文案工具，
 * 以及"基准单价 × 张数 × 折扣"的算术（{@link #totalPrice} / {@link #unitPrice}）。</p>
 *
 * <p><b>折扣建议值的出处</b>：产品原话「比如买 10 张都打 9.5 折、30 张统一 9 折这种」。
 * 本表取了 {@value #TIER_10_QTY} / {@value #TIER_20_QTY} / {@value #TIER_100_QTY} 三档，
 * 折扣 {@value #TIER_10_PER_MILLE}‰ / {@value #TIER_20_PER_MILLE}‰ / {@value #TIER_100_PER_MILLE}‰。
 * **这是建议值、可改**：站长在表单里能改张数与折扣，平台要调整这张表也只改本文件一处。</p>
 *
 * <p><b>金额口径</b>：总价保留 2 位（四舍五入），均价 = 总价 ÷ 张数、同样保留 2 位
 * —— <b>与 {@code TicketPackageController.save}（定制档）的算法逐字一致</b>，
 * 这样"界面显示的均价"与"保存后快照进批次的均价"不会出现两个数。</p>
 */
public final class TicketPreset {

    /** 9.5 折：10 张 */
    public static final int TIER_10_QTY = 10;
    public static final int TIER_10_PER_MILLE = 950;

    /** 9 折：20 张 */
    public static final int TIER_20_QTY = 20;
    public static final int TIER_20_PER_MILLE = 900;

    /** 8.5 折：100 张 */
    public static final int TIER_100_QTY = 100;
    public static final int TIER_100_PER_MILLE = 850;

    /** 折扣用千分比表达（950 = 9.5 折）：整数运算，不引入浮点误差。 */
    public static final int PER_MILLE_FULL = 1000;

    /** 千分比换成"折"要除的数：950‰ ÷ 100 = 9.5 折。 */
    private static final BigDecimal PER_MILLE_TO_ZHE = BigDecimal.valueOf(100);

    /** 平台预设的三档（顺序即展示顺序：先小后大）。 */
    private static final List<Tier> TIERS = List.of(
            new Tier(TIER_10_QTY, TIER_10_PER_MILLE),
            new Tier(TIER_20_QTY, TIER_20_PER_MILLE),
            new Tier(TIER_100_QTY, TIER_100_PER_MILLE));

    private TicketPreset() {}

    /** 一档预设：买 {@code qty} 张按 {@code discountPerMille}‰ 计价（950 = 9.5 折）。 */
    public record Tier(int qty, int discountPerMille) {}

    /** 平台预设档（不可变，顺序即展示顺序）。 */
    public static List<Tier> tiers() {
        return TIERS;
    }

    /**
     * 折扣文案（**由后端下发，前端禁止自造 950 → "9.5 折" 的映射表**）。
     *
     * <p>本仓有明确记录：展示文案散在前端各写一套曾导致"前端 2=水票、后端 2=现金"这类错位。
     * 千分比去掉末尾的 0 再折成"折"—— 900‰ → 「9 折」、950‰ → 「9.5 折」、850‰ → 「8.5 折」。</p>
     */
    public static String discountText(int discountPerMille) {
        // 千分比 → 折：950‰ = 0.95 = 9.5 折，也就是除以 100。
        // ⚠️ 写错过一次（除以 10 → 输出「95 折」），本类的单测把这条钉住了。
        BigDecimal zhe = BigDecimal.valueOf(discountPerMille)
                .divide(PER_MILLE_TO_ZHE, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return zhe.toPlainString() + " 折";
    }

    /**
     * 该档的总价 = 基准单价 × 张数 × 折扣，保留 2 位。
     *
     * @param baseUnitPrice 本站"一桶水的钱"；为空或非正时返回 {@code null}（拿不准就不给建议）
     */
    public static BigDecimal totalPrice(BigDecimal baseUnitPrice, Tier tier) {
        if (baseUnitPrice == null || baseUnitPrice.compareTo(BigDecimal.ZERO) <= 0 || tier == null) {
            return null;
        }
        return baseUnitPrice
                .multiply(BigDecimal.valueOf(tier.qty()))
                .multiply(BigDecimal.valueOf(tier.discountPerMille()))
                .divide(BigDecimal.valueOf(PER_MILLE_FULL), 2, RoundingMode.HALF_UP);
    }

    /**
     * 该档的均价 = 总价 ÷ 张数，保留 2 位。
     *
     * <p>⚠️ 必须由**总价**除回来，不能直接拿基准价乘折扣：后者会与
     * {@code TicketPackageController.save}（它按 {@code price / qty} 算均价并快照进批次）
     * 差一分钱，界面显示 8.55、批次存 8.54。本仓把"展示与快照同源"当硬规则。</p>
     */
    public static BigDecimal unitPrice(BigDecimal baseUnitPrice, Tier tier) {
        BigDecimal total = totalPrice(baseUnitPrice, tier);
        if (total == null || tier == null || tier.qty() <= 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(tier.qty()), 2, RoundingMode.HALF_UP);
    }

    /** 档位展示名（同样由后端下发）：如「10 张 9.5 折」。 */
    public static String title(Tier tier) {
        return tier == null ? null : tier.qty() + " 张 " + discountText(tier.discountPerMille());
    }
}
