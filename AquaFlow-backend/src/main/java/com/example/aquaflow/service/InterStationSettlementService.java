package com.example.aquaflow.service;

import java.util.Map;

/**
 * 站间结算（跨站外派单的「谁欠谁、欠多少、什么时候算办完」）。
 * 正本口径：{@code docs/design/31-站间结算算例-水票计价-决策件.md}（§6.2 算法 / §8 已拍板）。
 *
 * <p><b>口径一句话</b>：一笔跨站单里，</p>
 * <pre>
 *   应收（营收归谁）  = coalesce(settle_station_id, delivery_station_id, station_id)
 *   实收（钱在谁手上）= payment_record.station_id（status=2 已支付 那条）
 *   ⇒ 站间应付 = 收款站该给营收站的「本单营收」（非票单）或「票的实付价值」（票单，§8.1）
 * </pre>
 * <p>「谁欠谁」由此**实时算出**（零迁移，两站净额相加恒为 0，可作断言），
 * 台账表只在两种**人工动作**下落行：站长改价、站长登记结清。
 * 这样"欠多少"永远与订单/支付流水一致，不依赖在 6 个写入点挂钩子。</p>
 *
 * <p>⚠️ <b>只算已收款且未取消的单</b>（{@code payment_status = 2}、{@code status != 5}）：
 * 「不能拿订单已送达当成钱已付」（{@code docs/design/16} §9.1）。</p>
 *
 * <p>⚠️ <b>押金不进站间结算</b>：押金/水票/桶权益是**客户在归属站的资产**，
 * 不是营收（{@code util/StationUtil} 的口径：营收 = 水费 + 配送费 + 楼层费）。</p>
 */
public interface InterStationSettlementService {

    /**
     * 本站的站间结算台账（只读，现算）。
     *
     * <p>返回 {@code {items, receivableAmount, payableAmount, netAmount, ...}}：
     * 每个 item 带 {@code direction} = {@code RECEIVE}（别人欠本站）/ {@code PAY}（本站欠别人）、
     * 计价依据与状态的**中文文案**（后端下发的唯一来源）、以及该单三条口径下的金额候选
     * （便于站长理解"为什么是这个数"）。</p>
     */
    Map<String, Object> ledgerOf(Long stationId);

    /**
     * 本站**未结清**的跨站单数 —— 供站长待办卡（{@code PendingItem.INTER_STATION_UNSETTLED}）当角标。
     *
     * <p>⚠️ 它必须与 {@link #ledgerOf} 的 {@code unsettledCount} **恒等**
     * （"角标说 3、点进去 0 条"是这套待办机制最要命的失败形态）。
     * 所以实现里**不另写一套计数**，就是复用同一处读数。</p>
     */
    int unsettledCountOf(Long stationId);

    /**
     * 登记结清：**付款方**（钱在它手上的那一站）确认这笔已经付给对方。
     *
     * @param note 结清凭据说明（转账流水号 / 经手人）—— 线下动作只留痕，系统不假装打款
     * @throws com.example.aquaflow.exception.BusinessException 非付款方 / 该单不在台账 / 已被冲销
     */
    Map<String, Object> settle(Long stationId, Long orderId, String note, Long operatorId);

    /**
     * 改价：**卖票站**（= 订单归属站 = 收票钱的那一站）把默认的「折算实付」改成「按挂牌价」。
     * 差价由它自己承担（{@code docs/design/31} §8.2）。
     *
     * <p>TODO(待拍板) <b>改价的时窗</b>：只许「未被接单」时改，还是送达之后也能补改？
     * （正本 {@code docs/design/16} §9.3 第 1 行的派生第 2 问；完整事实见 {@code docs/design/31} §8.4。）
     * 两种选择的差别：<b>只许未接单时改</b> = 价钱在接单前谈定，接单站不会被事后改账，代价是
     * 接单后发现价不对只能先冲销再重结；<b>送达后也能补改</b> = 能按实际协商的价结清
     * （<b>现在就是这一支</b>：只挡「已结清 / 已冲销」，不挡接单或送达之后），代价是已结过的款
     * 要能冲销重结。拍板后改这里：{@code InterStationSettlementServiceImpl.priceByListed} 加一道
     * 状态闸（接单/送达事实取 {@code orders}），或反过来把"随时可改"写死并删掉本标记。</p>
     */
    Map<String, Object> priceByListed(Long stationId, Long orderId, Long operatorId);

    /** 冲销：订单取消/退款后那笔应付不再成立（改状态、不删行）。 */
    Map<String, Object> reverse(Long stationId, Long orderId);
}
