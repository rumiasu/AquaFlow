package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 支付服务：下单试算、发起支付、收款确认、退款编排。
 *
 * <p><b>四条唯一性约定（改动前必读）：</b></p>
 * <ul>
 *   <li>支付状态的唯一真值是 {@code orders.payment_status}，本服务是它唯一的写方。</li>
 *   <li>{@code refundOrder} 是<b>「取消订单」的唯一编排入口</b>：
 *       退水票 → 退支付流水 → 退押金 → 清配送中桶 → 回补库存 → 置订单已取消，
 *       并带「已完成(4) / 已取消(5) 不得再取消」的状态门槛。
 *       客户取消、配送员拒单、站长解决/拒单、取消申请审批<b>全都汇到它</b>，不要另写一套
 *       —— 历史上各 Controller 各拼半套回滚，漏一步就留下"订单已取消但钱票没退"。</li>
 *   <li>{@code refundPayment} 是<b>「单笔支付流水手工退款」的唯一入口</b>（站长在订单详情页点「退款」）。
 *       它<b>只</b>处理这一笔钱：流水转已退款 + 补一条负金额冲正流水 + 水票支付的按原路径回补水票；
 *       <b>不</b>取消订单、<b>不</b>退押金、<b>不</b>清配送中桶、<b>不</b>回补库存 —— 那些是取消链的事。
 *       ⚠️ 两条路径的<b>凭据形状必须一致</b>（共用 {@code PaymentServiceImpl.insertRefundRecord}）
 *       与<b>水票回补口径必须一致</b>（共用 {@code restoreTicketsForOrder}）：</li>
 *   <li>金额与桶数一律由 {@code quote} 在<b>服务端</b>推导，不信任前端传入的任何数字。</li>
 * </ul>
 *
 * <p>渠道现状：真实微信支付未接入，当前可用的是现金（货到付款）与水票。
 * [2026-09-20] 新增<b>微信支付模拟渠道</b>（{@code app.payment.mock-wechat-pay}，默认 <b>false</b>）：
 * 开启时 {@code availableMethods()} 把微信置为可选、{@code createPayment} 对 method=1
 * <b>当场置为已付款</b>（只取代"真实付款"这一下：金额重算、活跃流水唯一键、押金入账、
 * 订单付款状态只前进等校验全部照跑）；关闭时行为与之前逐字一致。
 * <b>两条退款路径的口径随之联动</b>——模拟开启时微信退款也按"原路退回（模拟）"放行并在
 * {@code note} 里写明是模拟；关闭时维持原状：手工退款（{@code refundPayment}）对微信流水直接拒绝
 * （它的唯一产出就是"钱"），订单取消链（{@code refundOrder}）只记凭据 + 写明"需线下退款"，
 * 不阻断取消（取消还要连锁退押金 / 回补库存 / 清配送中桶）。取舍理由见两个方法的 javadoc。</p>
 */
public interface PaymentService {

    /** 创建支付记录 */
    PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                Long ticketWaterTypeId, Integer ticketQty, String note);

    /**
     * 服务端支付试算（quote）。
     * 按最终业务规则在服务端计算：单次上限、当前可持有桶、需新增押金桶、水费/押金/总金额。
     * 不信任前端传入的任何金额或桶数。
     *
     * <p>⚠️ [v35] 与 {@code OrderServiceImpl.createOrder} <b>必须同口径</b>：配送费与楼层费两边
     * 都调 {@code DeliveryFeeService.calcForOrder}，水费与桶数口径也一致。
     * {@code addressId} 用于算配送范围与楼层费；不传则距离按"算不出来"处理
     * （不收远程费、不拦单），并在返回的 {@code warnings} 里说明。</p>
     */
    Map<String, Object> quote(Long customerId, Long stationId, Integer paymentMethod,
                              List<Map<String, Object>> items, Long addressId);

    /** 确认支付（微信回调/手动确认） */
    void confirmPayment(Long paymentId);

    /**
     * 配送完成收款确认：线下订单标记已收款 → 订单真正完成（COMPLETED）。
     * 仅线下方式（现金/微信转账）可被确认；水票视同已付。
     */
    void confirmOrderCollection(Long orderId);

    // [2026-09-16 按产品决定删除] 原 `void unconfirmOrderCollection(Long orderId);`
    //   做的事是把 已完成(4) 倒回 已送达(3)，属于**状态倒滚**，且回滚后 payment_status 仍留在
    //   已付款(2)，产生「已送达 + 已付款」这种自相矛盾的组合。产品口径：订单状态只前进，
    //   已完成的收款不许撤销；真要退钱走退款流程（`refundOrder`，其门槛是 OrderStatus.isCancellable，
    //   即 已完成/已取消 不可取消）。删除时全仓零调用点（仅接口 + 实现 + 一处注释提到它）。
    //   ⚠️ 不要再把它加回来，也不要新增任何「撤销确认收款」端点：
    //   `PaymentFlowIntegrationTest.noUnconfirmCollectionEndpoint` 会因此变红。

    /** 货到付款确认（配送员确认收到现金） */
    void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount);

    /** 水票支付（锁定水票，按站隔离） */
    void lockTicketPayment(Long orderId, Long customerId, Long productId, int qty, Integer orderStationId);

    /** 配送完成后扣减水票 */
    void deductTickets(Long orderId);

    /**
     * 站长手工退款：<b>单笔支付流水</b>退款（对应 {@code PUT /api/payments/{id}/refund}，员工端订单详情页
     * 的「支付流水 → 退款」入口）。
     *
     * <p>[2026-09-18] 产品口径「退款必须原路返回」，本方法的四条行为都由此而来：</p>
     * <ol>
     *   <li>原流水 CAS 成已退款(3)（并检查受影响行数）；</li>
     *   <li><b>补一条负金额冲正流水</b> —— 此前只改原流水状态，资金流水里看不到这笔支出，
     *       形状与 {@code refundOrder} 那条完全一致（共用 {@code insertRefundRecord}）；</li>
     *   <li>水票支付（{@code PayMethod.TICKET}）的<b>订单类</b>流水按原路径回补水票
     *       （共用 {@code restoreTicketsForOrder}，站别取归属站，过批次账）；
     *       站长的退款界面点了「退款」而客户的票一张没回来，是本仓最忌讳的"界面说做了、账上没动"；</li>
     *   <li>微信（{@code PayMethod.WECHAT}）流水<b>直接抛 {@code BusinessException} 拒绝</b>：
     *       渠道未接入，不能把流水标成已退款而钱没退；</li>
     *   <li>无订单的线上购票流水（{@code order_id IS NULL} 且 {@code ticket_qty > 0}）也<b>拒绝</b>：
     *       退款等于把已入账的水票扣回，票可能已被用掉，需要"余额不足即拒绝 + 过批次账"的完整实现，
     *       本批未做 —— 所以拒绝得明明白白，而不是静默走完前半段。</li>
     * </ol>
     *
     * <p>⚠️ 它<b>不取消订单</b>：退单笔钱不等于终止履约。要终止订单请走 {@code refundOrder}。</p>
     *
     * @param note 退款事由（站长填写，落进冲正流水的 note；{@code null} 时用默认文案）
     */
    void refundPayment(Long paymentId, String note);

    /**
     * 订单退款（取消订单触发）：对该订单所有已支付记录生成退款流水，更新订单状态。
     *
     * <p>⚠️ 它是<b>取消订单</b>的入口，不是"人工退一笔钱"的入口（后者是 {@code refundPayment}）。
     * 微信（{@code PayMethod.WECHAT}）渠道未接入时，本方法<b>不阻断取消</b>，
     * 但会在退款流水的 {@code note} 里写明"微信渠道未接入，需线下退款并登记"并记 WARN 日志
     * —— 历史微信单不该因为一笔退不出去的钱就永远取消不掉（取消还要连锁退押金 / 回补库存 /
     * 清配送中桶）。取舍的完整理由见 {@code PaymentServiceImpl.refundOrder} 与 {@code refundPayment}。</p>
     */
    void refundOrder(Long orderId, String reason);

    /**
     * 订单是否已有「已支付(PAID)」的支付流水。
     * [AQ-002][AQ-007] 完成配送时判定能否置「已付款」的唯一凭据 —— 杜绝配送员点一下"完成"就把
     * 未付款的微信单 / 未扣票的水票单变成已付款。
     */
    boolean hasPaidRecord(Long orderId);

    /**
     * 记录现金现场收款（货到付款），保证「订单已付款」必有对应支付流水。
     * 幂等：若该订单已存在 PAID 流水则直接返回，不重复记账。
     */
    void recordCashCollection(Long orderId);

    /**
     * 同上，但由调用方说明收款事由（落进 PaymentRecord.note）。
     *
     * <p>⚠️ <b>不要另写一条"补写 PAID 流水"的实现</b>：PAID 只能由支付链路写入
     * （见 {@code OrderWorkflowServiceImpl} 里那条领域原则）。多一条路径就多一处
     * 可能漏写凭证的地方，而对账<b>等式2</b>正是判「已付但无凭证」为不平。
     * 需要不同事由时走本重载，而不是复制一遍记账逻辑。</p>
     *
     * @param note 事由，例如「站长核销应收账款」；{@code null} 时用默认文案
     */
    void recordCashCollection(Long orderId, String note);

    /**
     * 订单的<b>结算站</b>变了（抢单 / 定向外派 / 退回池 / 召回 / 指定退回-同意）时，
     * 把这张单尚未确认的待收款流水改挂到新结算站 —— <b>谁结算谁催收</b>（v47，2026-09-18）。
     *
     * <p>流水的站别在"发起收款"那一刻就写死了，订单换站后它不会自己跟着走；不搬的结果是
     * 「履约站收了钱、凭据却挂在归属站」，而且两个站的「待确认收款」列表会各错一边。
     * 只搬 {@code PENDING} 的行：已收 / 已退的历史凭据不能改站。</p>
     *
     * @param settleStationId 订单新的结算站；{@code null} 时什么都不做
     */
    void relocatePendingCollection(Long orderId, Long settleStationId);

    /**
     * [AQ-009] 订单支付成功时入账预收桶押金（幂等）。
     * <p>押金不再在下单时入账（那时顾客一分未付、且金额取自客户端可被放大），
     * 而是统一改到订单支付成功（payment_status -> PAID）时，按订单 deposit_amount 入账并写流水。
     * 幂等：同一订单仅入账一次（按 related_order_id + PREPAID 去重），
     * 可被多处"置已付款"入口（线上确认 / 现金收款 / 水票扣减）安全重复调用。</p>
     */
    void applyDepositOnPaid(Long orderId);

    /** 查询订单支付记录 */
    List<PaymentRecord> listByOrderId(Long orderId);

    /**
     * 货到付款能不能用，不能用时给出**给用户看的原因** —— 全仓唯一判据（v48 起；两项配置已于 v49 撤回）。
     *
     * <p>两层：开关（该客户在该站是否被站长开通）→ 欠款即停（有逾期未结的现金单就不给新的赊账单）。
     * 下单与报价都必须调它，不要各写一套（分叉就会出现"报价能选、提交被拒"）。</p>
     *
     * @return {@code null} = 可用；否则是可直接展示的拒绝原因
     */
    String offlinePaymentBlockReason(Long customerId, Long stationId);

    /** 查询客户支付记录 */
    List<PaymentRecord> listByCustomerId(Long customerId);

    /** 查询所有支付记录（管理端）—— [AQ-052] 必须带 stationId，禁止全平台无过滤查询 */
    List<PaymentRecord> listAll(Long stationId, int limit);

    /** 查询支付记录（带过滤）—— [AQ-052] 必须带 stationId */
    List<PaymentRecord> listWithFilter(Long stationId, Integer status, Integer paymentMethod, int limit);

    /**
     * 校验客户在指定水站是否有线下支付权限
     * 双开关：水站总开关 + 客户授权
     */
    boolean canUseOfflinePayment(Long customerId, Long stationId);
}
