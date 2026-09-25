package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;

import java.util.Map;

/**
 * 订单工作流服务 —— 全系统**唯一的订单状态写入口**。
 *
 * <p>本服务存在的唯一理由：订单的「状态 / 支付状态 / 配送员 / 履约站」这四类字段，
 * 此前散落在 {@code DeliveryController}（18 处）与 {@code ManagerOrderController}（8 处）
 * 里被直接 {@code orderMapper.update(order)} 或 {@code updateStatus(...)} 改写。
 * 那些写法绕开了状态机、绕开了 CAS、也绕开了退款与押金入账的副作用，
 * 导致同一张订单可以经不同 HTTP 路径被写坏，并发下还会互相覆盖（lost update）。</p>
 *
 * <p><b>调用契约</b>：</p>
 * <ul>
 *   <li>Controller 只做「认证 + 参数解析 + 调用本服务 + 包装 Result」，<b>不得</b>直接触碰
 *       Orders / PaymentRecord / 库存 / 桶账表。</li>
 *   <li>每个方法自身完成：身份与站点校验 → 当前状态校验 → 状态 CAS（带 affected-row 检查）
 *       → 桶/库存/支付副作用 → 审计留痕，全部在**同一事务**内。</li>
 *   <li>业务前置不满足一律抛 {@code BusinessException}（code=1），由全局异常处理器统一转换；
 *       <b>绝不</b>在事务方法内 catch 后返回 {@code Result.error}，否则已发生的副作用会被提交。</li>
 *   <li>站点判定一律以 {@code AuthContext} 的服务端刷新值为准，不信任请求参数。</li>
 * </ul>
 */
public interface OrderWorkflowService {

    /* ========== 配送履约 ========== */

    /** 配送员接单：待配送 → 配送中（CAS，仅无主或已分配给本人时可接）。 */
    void acceptOrder(Long orderId);

    /** 确认线下（现金）收款：补写 PAID 流水 + 入账预收押金，已送达则顺带闭环为已完成。 */
    void confirmOfflinePay(Long orderId);

    /** 完成配送：回桶核对 + 桶权益结算 + 支付前置校验 + 状态 CAS。 */
    void completeDelivery(Long orderId, Map<String, Object> params);

    /* ========== 取消 / 拒单（单一编排入口） ========== */

    /** 配送员拒单：备注留痕 + 触发统一退款编排并置为已取消。 */
    void rejectOrder(Long orderId, String reason);

    /** 站长「解决/拒单」：与 {@link #rejectOrder} 同一编排，仅留痕文案不同。 */
    void resolveOrder(Long orderId, String reason);

    /** 站长拒单（简化版）：直接取消（走退款编排）或尝试外派进入抢单池。 */
    void stationReject(Long orderId, String reason, boolean tryDispatch);

    /* ========== 取消申请（已接单订单的取消须站长审批） ========== */

    /**
     * 配送员发起取消申请：写入 {@code order_transfer(kind=STAFF, subKind=CANCEL_REQUEST, PENDING)}，
     * <b>不改订单状态</b>——订单继续处于配送中/已送达，直到站长决策。
     *
     * <p>适用状态：配送中(2) / 已送达(3)。待配送(1) 尚未接单，配送员走 {@link #rejectOrder} 直接取消即可。</p>
     */
    void requestCancelByStaff(Long orderId, String reason);

    /**
     * 客户发起取消申请：写入 {@code order_transfer(kind=CUSTOMER, subKind=CANCEL_REQUEST, PENDING)}，
     * 同样不改订单状态。仅用于<b>已接单</b>（配送中/已送达）的订单；
     * 待配送订单客户仍可直接取消（{@code OrderService.cancelByCustomer}）。
     *
     * @param customerId 当前登录客户ID，用于校验订单归属，防止冒名取消他人订单
     */
    void requestCancelByCustomer(Long orderId, Long customerId, String reason);

    /**
     * 站长同意取消申请：走 {@code PaymentService.refundOrder} 完整退款链
     * （退水票 → 退支付流水 → 退押金 → 回补库存）并置订单为已取消，申请置 APPROVED。
     */
    void approveCancelRequest(Long orderId);

    /** 站长驳回取消申请：订单保持原状态继续履约，申请置 REJECTED。 */
    void rejectCancelRequest(Long orderId);

    /* ========== 派单 / 外派 / 召回 / 抢单 ========== */

    /**
     * 跨站外派：改写履约站并清空配送员（归属站不变），通知客户。
     *
     * @param riskAcknowledged 外派方对「押金 / 桶权益」风险的<b>显式确认</b>。
     *        涉押金/桶权益的单（判据见实现类 {@code involvesDepositOrBarrelRights}）
     *        未确认即拒绝，且不产生任何副作用；不涉押金的普通单不看这个字段（保持原状）。
     */
    void dispatchExternal(Long orderId, Long targetStationId, String reason, boolean riskAcknowledged);

    /**
     * 站长外派：指定目标站，或（targetStationId 为 null 时）放入抢单池。
     *
     * <p><b>涉押金/桶权益的单禁止放入抢单池</b>（无论确认与否，直接拒绝）；指定目标站时
     * 需要 {@code riskAcknowledged=true}，与 {@link #dispatchExternal} 同一道闸门
     * ——两个端点干的是同一件事，只堵一个等于没堵。</p>
     */
    void outsource(Long orderId, Long targetStationId, String reason, boolean riskAcknowledged);

    /** 取消外派：召回本站待分配。 */
    void cancelDispatch(Long orderId);

    /** 抢单池抢单：本站认领并指定配送员（CAS，仅当订单仍在池中）。 */
    void claimPool(Long orderId, Long targetStaffId);

    /**
     * 跨站外派风险提示文案（下发给前端，前端原样展示、不得自编）。
     *
     * @return 本单涉押金/桶权益时返回提示与出路文案；否则返回 {@code null}
     *         （前端据此决定要不要在提交前弹确认）
     */
    String crossStationRiskNote(Orders order);

    /* ========== 站内转单 ========== */

    /**
     * 站长分配配送员（状态保持待配送，等配送员接单）。
     *
     * @param riskAcknowledged <b>接收站</b>对「押金 / 桶权益」风险的显式确认：
     *        他站定向外派过来的涉押金/桶权益单，未确认即拒绝（不涉押金的单不要求）
     */
    void assignToStaff(Long orderId, Long targetStaffId, boolean riskAcknowledged);

    /** 配送员/站长转让订单给本站其他员工。 */
    void transferToStaff(Long orderId, Long targetStaffId, String reason);

    /** 取消待决策的转让。 */
    void cancelTransfer(Long orderId);

    /** 配送员认领无人认领的订单（CAS）。 */
    void claimTransfer(Long orderId);

    /** 拒绝认领。 */
    void rejectTransfer(Long orderId);

    /* ========== 退回站长 ========== */

    /** 配送员退回站长：待配送/配送中 → 待配送，保留原配送员，待站长决策。 */
    void returnToStation(Long orderId, String reason);

    /** 站长同意退回：清空配送员，转为真正待分配。 */
    void approveReturn(Long orderId);

    /** 站长拒绝退回：回到配送中，由原配送员继续履约。 */
    void rejectReturn(Long orderId);

    /* ========== 站间指定退回 ========== */

    /** 目标站发起指定退回：打「待确认」标记，保留原履约站與配送员。 */
    void directedReturn(Long orderId);

    /** 原归属站同意指定退回。 */
    void directedReturnApprove(Long orderId);

    /** 原归属站拒绝指定退回：回到配送中。 */
    void directedReturnReject(Long orderId);
}
