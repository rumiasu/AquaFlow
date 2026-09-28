package com.example.aquaflow.constant;

/**
 * 站间结算的「计价依据」（{@code inter_station_settlement.basis}）—— **唯一正本**。
 *
 * <p>2026-09-27 产品拍板（正本 {@code docs/design/31-站间结算算例-水票计价-决策件.md} §8）：
 * <b>默认折算实付，主要依靠站长选</b>。所以三种取值的分工是：</p>
 * <ul>
 *   <li>{@link #REVENUE} —— <b>非票单</b>：钱是在本站（归属站）收的（微信/已收现金），
 *       履约站在别站 ⇒ 应付 = 本单**营收** = {@code water_amount + delivery_fee + floor_fee}。
 *       ⚠️ <b>不含押金</b>：押金是客户资产、认归属站，不是营收（{@code util/StationUtil} 的口径）。</li>
 *   <li>{@link #TICKET_ACTUAL} —— <b>水票单的默认口径</b>：票是预付，钱在**买票时**就进了归属站，
 *       所以只能按"这张票当初实付多少钱"折算。**逐张**取 {@code ticket_record.unit_price} 求和：
 *       一张单可能跨批次消耗（消耗按 FIFO），逐张求和天然等于"本单实际消耗的那几张的实付价"，
 *       而且**与退款回补同源**（回补用的就是同一个数）。</li>
 *   <li>{@link #TICKET_LISTED} —— <b>卖票站站长可选</b>的挂牌价口径（{@code docs/design/31} §8.2）：
 *       使 {@code TICKET_LISTED} 的动机是"久无接单时提高接单站收益以促成接单"（§8.3），
 *       **差价由卖票站自己承担**（它才是付钱的那一方）。取值 = {@code orders.water_amount}
 *       （= Σ{@code order_item.subtotal}，本来就是按挂牌价算出来的水费）。</li>
 * </ul>
 *
 * <p>⚠️ 与 {@link PayMethod} 的关系：本枚举**不按支付方式分支**，而是按"这笔钱当初怎么进来的"分支。
 * 票单（{@code payment_method = 3}）用 2/3，其余用 1 ——
 * 判据在本类里给成 {@link #defaultFor(Integer)}，别在调用方各写一遍 if。</p>
 */
public class SettleBasis {

    /** 1 本单营收照实结（非票单）：水费 + 配送费 + 楼层费。 */
    public static final int REVENUE = 1;

    /** 2 水票折算实付（默认）：逐张 {@code ticket_record.unit_price} 求和。 */
    public static final int TICKET_ACTUAL = 2;

    /** 3 水票按挂牌价（卖票站站长可选）：{@code orders.water_amount}。 */
    public static final int TICKET_LISTED = 3;

    /**
     * 该支付方式的**默认**计价依据。
     *
     * <p>票单 = {@link #TICKET_ACTUAL}（§8.1 默认折算实付），其余 = {@link #REVENUE}。
     * ⚠️ 水票在这里是拿 {@link PayMethod#TICKET} 判的，别写字面量 3。</p>
     */
    public static int defaultFor(Integer paymentMethod) {
        return PayMethod.TICKET == (paymentMethod == null ? -1 : paymentMethod)
                ? TICKET_ACTUAL : REVENUE;
    }

    /** 是否为合法计价依据（请求体里的 basis 一律用它校验，同 AGENTS §6 的枚举白名单要求）。 */
    public static boolean isValid(Integer basis) {
        return basis != null
                && (basis == REVENUE || basis == TICKET_ACTUAL || basis == TICKET_LISTED);
    }

    /** 是否为"按票折价"的口径（只有这两种才用到 {@code ticket_qty} / {@code unit_price}）。 */
    public static boolean isTicketBased(Integer basis) {
        return basis != null && (basis == TICKET_ACTUAL || basis == TICKET_LISTED);
    }

    /** 展示文案（**后端下发的唯一来源**，前端禁止自带映射表，见 AGENTS §6）。 */
    public static String textOf(Integer basis) {
        if (basis == null) {
            return "未指定";
        }
        switch (basis) {
            case REVENUE:        return "本单营收照实结";
            case TICKET_ACTUAL:  return "水票折算实付";
            case TICKET_LISTED:  return "水票按挂牌价";
            // 兜底不许把未知值说成某个已知值（同 PayMethod.textOf 的历史事故）。
            default:             return "未知计价依据";
        }
    }

    private SettleBasis() {}
}
