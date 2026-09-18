package com.example.aquaflow.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 配送员计件工资（v37）：收益明细 + 结算单。规格见 {@code docs/design/18}。
 *
 * <p><b>本服务是 {@code staff_earning} 的唯一写入口</b>。三条不变量：</p>
 * <ol>
 *   <li><b>方向由 kind 决定，调用方一律传正数</b>（唯一的例外是 {@code ADJUST}）——
 *       符号只在服务层转一次，不允许各调用点自己 negate（本仓在押金上踩过"两处各写一次、
 *       其中一处写反"的事故）。</li>
 *   <li><b>收益在订单进入「送达」的那次状态变更中产生</b>，靠 {@code uk_earning_auto} 幂等。
 *       钉在这个时点的额外好处：订单状态只前进、{@code isCancellable} 已拦掉已完成/已取消，
 *       所以能取消的单一定还没产生收益 —— <b>不存在"收益发了又要撤回"的回滚问题</b>。</li>
 *   <li><b>归属站 = 履约站</b>（{@code orders.delivery_station_id}）：工钱是履约成本，跟出车的人走；
 *       跨站外派单由外派站的配送员跑腿，工钱就该外派站发。</li>
 * </ol>
 *
 * <p><b>工钱不参与客户对账</b>：{@code ReconciliationService} 的等式都是客户/资产维度，
 * 把工资算进去会让每天 03:00 的日结必然报不平、淹没真问题。工钱走独立等式 E-PAY。</p>
 */
public interface StaffEarningService {

    /**
     * 完成配送时产生收益（幂等，可重复调用）。
     *
     * <p>订单没有配送员、或本站没配计件单价时静默跳过（记日志），不报错 ——
     * "没配计件"是合法的经营状态，不是故障。</p>
     */
    void recordDeliveryEarnings(Long orderId);

    /**
     * 生成结算单：把该期间内**未结算**的明细挂到新结算单上并算出合计。
     *
     * @param periodStart 期间起（含）
     * @param periodEnd   期间止（含）
     * @return 结算单 id
     */
    Long generatePayroll(Long stationId, Long staffId, LocalDate periodStart, LocalDate periodEnd, String note);

    /** 确认结算单（草稿 → 已确认）。确认后明细锁定，不允许再改动。 */
    void confirmPayroll(Long stationId, Long payrollId);

    /** 标记已发放（已确认 → 已发放）。发钱是线下动作，这里只留痕：发放时间 + 操作人。 */
    void markPayrollPaid(Long stationId, Long payrollId);

    /**
     * 追加一条人工调整（站长在结算单上加减）。
     *
     * @param itemId 自定义条目（v44，可空）。<b>传了它就只能传正数金额</b> —— 方向由条目决定；
     *               为 null 时是老的自由文本调整，{@code amount} 可正可负
     *               （这是唯一允许调用方给符号的 kind）
     */
    void adjustEarning(Long stationId, Long staffId, Long itemId, BigDecimal amount, String note);
}
