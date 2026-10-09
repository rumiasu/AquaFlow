package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DispatchKind;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerNotification;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerNotificationMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.OrderTransferMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;

/**
 * {@link OrderWorkflowService} 实现。
 *
 * <p><b>Phase C 变更要点</b>：本类的方法体由拆分前的 {@code DeliveryController} 与
 * {@code ManagerOrderController} 原样迁移而来（校验顺序、错误文案、副作用次序均保持不变），
 * 只做了三类改造：</p>
 * <ol>
 *   <li>原来 {@code return Result.error("...")} 的「提前返回」改为抛 {@code BusinessException}。
 *       这是**必须**的：旧写法在 {@code @Transactional} 方法内提前 return，
 *       会把此前已经发生的副作用（桶账 applyDelivery、barrel_record 流水）**提交**掉，
 *       导致「未付款订单被拒完成，但桶账已经动了」。</li>
 *   <li>所有状态 / 支付状态 / 配送员 / 履约站写入改为带 expected-state 的 CAS，
 *       并检查受影响行数；纯备注改为 DB 侧原子追加（{@code appendSpecialNote}）。</li>
 *   <li>删除「读整行 → 改内存 → 整行 update」的 read-modify-write，消除丢失更新。</li>
 * </ol>
 */
@Service
@Slf4j
public class OrderWorkflowServiceImpl implements OrderWorkflowService {
    @Autowired private com.example.aquaflow.service.OrderBarrelPurchaseService orderBarrelPurchases;
    @Autowired private com.example.aquaflow.service.DispatchAgreementService dispatchAgreements;

    // 外派备注标记（一键外派 / 指定外派）的正本是 constant/DispatchKind 的 NOTE_* 常量 ——
    // 站长端「外派」页签按它把外派分成两个子页签（见 DispatchKind.ofNote 的注释：
    // 这两种形态在库里没有结构化标记，唯一判据就是备注文案，所以拼备注必须用那份常量）。
    // ⚠️ 不要在本类里另抄一份字符串字面量：抄一份 = 又开一个口径，而判错的后果是
    // 订单在站长端落进错误的子页签（一键外派 vs 指定外派），且全程不报错。

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 库存预留凭据：① 完成配送时**出库**（本类唯一扣实物的地方）；② 换站（放池/外派/抢单/召回）
     * 时把凭据搬到新的履约站。见 {@code docs/design/28-库存预留与履约凭据.md}。
     */
    @Autowired
    private com.example.aquaflow.service.InventoryReservationService inventoryReservationService;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderTransferMapper orderTransferMapper;
    @Autowired private com.example.aquaflow.mapper.OrderCancelResultMapper cancelResults;

    /**
     * 配送员计件工资（v37）。
     *
     * <p>⚠️ 收益只在<b>本类的 completeDelivery 里、状态 CAS 成功之后</b>产生，
     * 不要在别处补算：钉在这个时点的好处是订单状态只前进、`isCancellable` 已拦掉
     * 已完成/已取消，所以能取消的单一定还没产生收益 —— 不存在"收益发了又要撤回"的回滚问题。</p>
     */
    @Autowired
    private com.example.aquaflow.service.StaffEarningService staffEarningService;

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    /** 桶权益总账：全系统唯一的桶账写入口 */
    @Autowired
    private BarrelLedgerService barrelLedgerService;

    @Autowired
    private BarrelService barrelService;

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private CustomerNotificationMapper customerNotificationMapper;

    /* ==================================================================
     *  基础设施：校验 / 留痕 / 通知
     * ================================================================== */

    private Orders requireOrder(Long orderId) {
        Orders o = orderMapper.getById(orderId);
        if (o == null) throw new BusinessException("订单不存在");
        return o;
    }

    private Orders requireOrderForUpdate(Long orderId) {
        Orders order = orderMapper.getByIdForUpdate(orderId);
        if (order == null) throw new BusinessException("订单不存在");
        return order;
    }

    /** 履约站（delivery_station_id 优先，回退 station_id） */
    private Long deliveryStation(Orders o) {
        return StationUtil.deliveryStation(o);
    }

    /**
     * 「钱已经到手（或本来就该到付）」—— 决定一张单**能不能进站长/配送员视野、能不能被接单**的唯一判据
     * （2026-09-18 产品裁定：只有已支付的订单才推给站长、才允许接单）。
     *
     * <p>⚠️ 这个判据在<b>三处</b>必须一致，改一处就得改三处，否则「站长看得到、配送员接不了」两边分叉：
     * ① {@code OrderMapper.listPendingByStationId}、② {@code OrderMapper.listStationPendingUnassigned}、
     * ③ 本方法（接单 / 分配的业务闸门，防"列表里看不到但 id 可编造"）。</p>
     *
     * <p>只有两条路：<b>已付(2)</b>（微信/水票都在付款成功那一刻置 2，水票的扣票就发生在
     * 客户端下单后那次支付请求里 —— 所以"水票单算已付"是**由扣票成功**体现的，
     * 不是靠下单时就推）与<b>现金(2)</b>（货到付款：钱要当面收，不能等付了才派人；
     * 客户没开通货到付款时下单就被拒，所以"现金单" ≡ "允许货到付款的客户"）。
     * TODO(微信支付接入)：回调置 {@code payment_status = 2} 即自动命中第一条，本方法不用改。</p>
     */
    private boolean isPaidOrPayOnDelivery(Orders order) {
        if (order == null) {
            return false;
        }
        if (Integer.valueOf(PaymentStatus.PAID).equals(order.getPaymentStatus())) {
            return true;
        }
        return Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod());
    }

    /** 强制当前水站非空（未绑站直接拒绝，fail-closed），并严格比对履约站 */
    private void checkStationOwnership(Orders order) {
        Long myStationId = AuthContext.requireStationId();
        Long orderStation = deliveryStation(order);
        if (orderStation == null || !myStationId.equals(orderStation)) {
            throw new BusinessException("无权操作他站订单");
        }
    }

    private void checkDeliverySelf(Orders order) {
        Long staffId = AuthContext.getUserId();
        if (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(staffId)) {
            throw new BusinessException("仅可操作分配给自己的订单");
        }
    }

    /**
     * 调度类动作（定向外派 / 放抢单池 / 退回池）的判权 ——
     * <b>「还没被接单之前，这单仍算归属站的」</b>（2026-09-22 产品裁定）。
     *
     * <p>放行两种人：</p>
     * <ol>
     *   <li><b>当前履约站</b>（{@link #deliveryStation}）—— 谁在办谁说了算；</li>
     *   <li><b>归属站，且这单还没被接单</b>（状态仍是 {@code 待配送(1)}）。</li>
     * </ol>
     *
     * <p>⚠️ 为什么要加第 2 条：定向外派会把 {@code delivery_station_id} <b>直接改成目标站</b>，
     * 而对方<b>接单</b>才会把状态推到 {@code 配送中(2)}。中间这段"已指定、没人接"的窗口里，
     * 按第 1 条判就只剩目标站能操作 —— 归属站<b>连改派都做不到</b>：小程序上「重新外派」
     * 点了必报"仅能操作本站订单"，而按钮又是照 {@code status === 1} 显示的。</p>
     *
     * <p>⚠️ 一旦被接单（状态 ≥ 2），第 2 条自动失效 —— 与「被接单后归接单站管」是同一条线：
     * 召回（{@code cancelDispatch}）只收 {@code 待配送(1)}，两者口径一致。
     * 见 AGENTS §1.1 的 2026-09-22 裁定。</p>
     */
    private void requireDispatchRight(Orders order, Long myStationId) {
        if (AuthContext.isDelivery()) {
            checkStationOwnership(order);
            checkDeliverySelf(order);
        }
        if (myStationId != null && myStationId.equals(deliveryStation(order))) {
            return;
        }
        boolean notAcceptedYet = order.getStatus() != null && order.getStatus() == OrderStatus.PENDING;
        if (notAcceptedYet && myStationId != null && myStationId.equals(order.getStationId())) {
            return;
        }
        throw new BusinessException("仅能操作本站订单；已被别站接单的，只能由接单站处理");
    }

    private void log(String action, Long orderId, Map<String, Object> detail) {
        auditLogService.log("ORDER", action, "order:" + orderId, detail != null ? detail.toString() : "", null);
    }

    /** 目标员工必须是本站在职的配送员或站长 */
    private Staff requireDelivery(Long staffId, Long stationId) {
        Staff s = staffMapper.getById(staffId);
        if (s == null || s.getStatus() == null || !Integer.valueOf(1).equals(s.getStatus())) {
            throw new BusinessException("目标员工不在职");
        }
        String role = s.getRole();
        if (!"DELIVERY".equals(role) && !"STATION_MANAGER".equals(role)) {
            throw new BusinessException("目标不是本站配送员或站长");
        }
        if (s.getStationId() == null || !s.getStationId().equals(stationId)) {
            throw new BusinessException("只能将单转给本站员工");
        }
        return s;
    }

    private void notifyCustomerRejected(Long customerId, Long orderId, String reason) {
        if (customerId == null) return;
        CustomerNotification n = new CustomerNotification();
        n.setCustomerId(customerId);
        n.setType("REJECTED");
        n.setTitle("订单已取消");
        n.setContent("订单 #" + orderId + " 因水站原因被取消，拒绝接单。"
                + (reason != null && !reason.isEmpty() ? "原因：" + reason : ""));
        n.setRelatedOrderId(orderId);
        n.setIsRead(0);
        n.setCreateTime(LocalDateTime.now());
        customerNotificationMapper.insert(n);
    }

    private void notifyCustomerTempDispatch(Long customerId, Long orderId, Long targetStationId) {
        if (customerId == null) return;
        String stationName = "";
        if (targetStationId != null) {
            Station s = stationMapper.getById(targetStationId);
            if (s != null && s.getName() != null) stationName = s.getName();
        }
        CustomerNotification n = new CustomerNotification();
        n.setCustomerId(customerId);
        n.setType("TEMP_DISPATCH");
        n.setTitle("临时外派配送");
        n.setContent("因水站原因，订单 #" + orderId + " 临时由"
                + (stationName.isEmpty() ? "其他水站" : stationName + "水站") + "代替送达。");
        n.setRelatedOrderId(orderId);
        n.setIsRead(0);
        n.setCreateTime(LocalDateTime.now());
        customerNotificationMapper.insert(n);
    }

    /** 写入一条待决策转单记录（结构化权威状态源，[AQ-015]） */
    private Long insertTransfer(Long orderId, String kind, String subKind,
                                Long fromStaffId, Long toStaffId, Long fromStationId, String reason) {
        OrderTransfer ot = new OrderTransfer();
        ot.setOrderId(orderId);
        ot.setKind(kind);
        ot.setSubKind(subKind);
        ot.setFromStaffId(fromStaffId);
        ot.setToStaffId(toStaffId);
        ot.setFromStationId(fromStationId);
        ot.setStatus(OrderTransfer.STATUS_PENDING);
        ot.setReason(reason);
        ot.setOperatorId(AuthContext.getUserId());
        orderTransferMapper.insert(ot);
        return ot.getId();
    }

    /** 指定退回等待原站决策；普通同事转让仍按 C-12 允许原负责人履约。 */
    private void requireNoPendingDirectedReturn(Long orderId) {
        OrderTransfer request = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_DIRECTED);
        if (request != null && OrderTransfer.SUB_DIRECTED_RETURN.equals(request.getSubKind())) {
            throw new BusinessException("指定退回正在等待原水站处理，请先处理申请");
        }
    }

    /**
     * 订单取消 / 拒单的**单一编排入口**。
     * <p>所有「取消订单」的业务动作（客户取消、配送员拒单、站长拒单/解决）都必须经此，
     * 由 {@code PaymentService.refundOrder} 统一完成：退水票 → 退支付流水 → 退押金 →
     * 清配送中桶 → 回补库存 → 置订单已取消。此前 Controller 各自拼装半套回滚逻辑，
     * 漏掉哪一步就会留下「订单已取消但钱/票/桶没退」的账。</p>
     */
    private void cancelWithRefund(Long orderId, String reason, String notePart) {
        orderMapper.appendSpecialNote(orderId, notePart);
        paymentService.refundOrder(orderId, reason);
    }

    /* ==================================================================
     *  配送履约
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptOrder(Long orderId) {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.requireStationId();

        // Read authorization from the current locked row, before accepting or overwriting its assignee.
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        requireNoPendingDirectedReturn(orderId);
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单当前状态不可接单");
        }
        // 与两张待办列表同一道闸门：没收到钱的单不进站长/配送员视野，也接不了。
        // 渠道未接入的微信单会一直停在这里 —— 这是有意的（客户没付钱，货不该出门）。
        if (!isPaidOrPayOnDelivery(order)) {
            throw new BusinessException("该订单尚未支付，暂时不能接单：请客户完成支付后再配送");
        }
        if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(staffId)) {
            throw new BusinessException("该订单已分配给其他配送员");
        }
        // [2026-09-26 产品裁定] **配送员只能接派给自己的单**：产品原话「如果是未分配的订单，
        // 不应该直接显示给配送员吧 —— 现在站长还没分配，刚同意入站就能看见订单了，就能接单了」。
        // 站长不受这条限制：他自己也送水，"看到未分配就自己接了"是正常动作（前台的接单按钮
        // 对站长同样开放），所以这里按角色分叉，而不是一刀切要求"必须先被分配"。
        if (AuthContext.isDelivery() && order.getDeliveryStaffId() == null) {
            throw new BusinessException("该订单还没分配配送员，请联系站长分配后再接单");
        }
        // Dispatch clears the assignee. A risky incoming order must first pass the existing
        // receiving-station assignment confirmation, including a manager assigning to themselves.
        boolean incomingRisk = !stationId.equals(order.getStationId()) && involvesDepositOrBarrelRights(order);
        if (incomingRisk && order.getDeliveryStaffId() == null) {
            throw new BusinessException("接收他站押金/桶权益单前，请站长先确认风险并分配配送员（可分配给自己）");
        }

        // 原子接单：仅当 status=1 才更新，返回受影响行数（乐观锁）
        int affected = orderMapper.updateStatusIfPENDING(orderId, OrderStatus.DELIVERING, staffId);
        if (affected == 0) {
            throw new BusinessException("接单失败，订单状态已变更，请刷新后重试");
        }
        dispatchAgreements.accepted(order,stationId);
        // 旧实现把 "[接单]" 只写进内存对象、从未落库（没有后续 update），此处改为原子追加。
        orderMapper.appendSpecialNote(orderId, "[接单] 配送员ID=" + staffId);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", orderId);
        d.put("deliveryStaffId", staffId);
        log("ACCEPT", orderId, d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmOfflinePay(Long orderId) {
        Long staffId = AuthContext.getUserId();
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        requireNoPendingDirectedReturn(orderId);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        // 仅现金(货到付款)订单需配送员现场确认收款；微信(线上回调)与水票(已扣减)不在此处理
        if (order.getPaymentMethod() == null || !Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod())) {
            throw new BusinessException("仅现金(货到付款)订单可确认收款");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.DELIVERING && cur != OrderStatus.DELIVERED) {
            throw new BusinessException("当前状态不可确认线下收款");
        }

        // 支付状态守卫：[2026-09-30 修 F-00] 只接受「钱还没到手」(0 未付 / 1 待收款) → 已付(2)。
        //
        // 原实现是 `updatePaymentStatusIf(orderId, payCur, PAID)`，而 payCur 是**刚读到的当前值**
        // ⇒ expected 恒等于现值，这个 CAS 对"从哪个状态迁入"没有任何限制，等于没有守卫。
        // 后果链完整可复现：PaymentServiceImpl.refundPayment 把 payment_status 置 3（已退款）
        // 但**不动 status**，而本方法上面的状态守卫只要求 status ∈ {配送中(2), 已送达(3)}
        // ⇒ 已退款单能在这里被改回已付款(2)；随后 recordCashCollection 的幂等判据
        // `countByOrderIdAndStatus(orderId, PAID)` 已被改写成 0，于是它会**再插一条全额 PAID 流水** ——
        // 客户拿到了退款，报表上却显示这笔钱又被收了一次，而对账等式2 不会报
        // （"已付款"与"有 PAID 凭证"同时成立）。这违反不变量「支付状态只前进、不倒滚，
        // 3/4 是终态；唯一写 2 的入口是 markPaidIfCollectable，绝不复活 3/4」(AGENTS.md §1.1)。
        //
        // 正确做法：走 markPaidIfCollectable —— 它的 SQL 写死 `payment_status in (0, 1)`，
        // 终态由数据库那一层保证，调用方改不动。3/4 单独给可读文案，不要笼统报"状态已变更"。
        int payCur = order.getPaymentStatus() != null ? order.getPaymentStatus() : PaymentStatus.UNPAID;
        if (payCur == PaymentStatus.REFUNDED) {
            throw new BusinessException("该订单已退款，不能重复收款");
        }
        if (payCur == PaymentStatus.CANCELLED) {
            throw new BusinessException("该订单已取消，不能确认收款");
        }
        // 已付(2) 即幂等跳过 —— 这条短路不能丢：重复点确认时若不跳过就会拿到 0 行、
        // 把一次正常的重复操作报成错误（详见 markPaidIfCollectable 的 javadoc）。
        if (payCur != PaymentStatus.PAID) {
            if (orderMapper.markPaidIfCollectable(orderId) == 0) {
                throw new BusinessException("支付状态已变更，请刷新后重试");
            }
        }
        // 已送达则顺带闭环为已完成；[2026-09-30 修 F-11] 必须取受影响行数 ——
        // 并发下订单可能已被别的路径改走（CAS 命中 0 行），若继续往下走收款/押金，订单会
        // 静默停在已送达、界面却说已完成。
        if (cur == OrderStatus.DELIVERED) {
            if (orderMapper.updateStatusIf(orderId, OrderStatus.DELIVERED, OrderStatus.COMPLETED) == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
        }
        orderMapper.appendSpecialNote(orderId, "[线下收款确认] 配送员ID=" + staffId);

        // [AQ-002] 置「已付款」必须补写 PAID 支付流水，否则订单已付款却无凭证，日结对不上
        paymentService.recordCashCollection(orderId);
        // [AQ-009] 收款后入账预收桶押金（幂等）
        paymentService.applyDepositOnPaid(orderId);

        log("CONFIRM_OFFLINE_PAY", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeDelivery(Long orderId, Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        requireNoPendingDirectedReturn(orderId);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        if (order.getStatus() != OrderStatus.DELIVERING) {
            throw new BusinessException("该订单当前状态不可完成配送");
        }
        // docs/design/16-范围决策与实施路线图.md C-12：保持原负责人正常履约。
        // “先撤回才能送达”未获批准，不实施；送达后只收尾失效转让，不改变资金责任。

        // 首次桶装水订单：押金桶无需回桶，直接跳过回桶核对
        boolean isFirstBarrelOrder = Boolean.TRUE.equals(order.getFirstBarrelOrder())
                && !barrelLedgerService.independentOrder(orderId);

        // ===== 配送员上报楼层（选填，v43）=====
        // 楼层补贴是给配送员的钱，只有他知道自己爬了几层 —— 所以有他自己的口径 + 可核对的凭证。
        // ⚠️ 只写一次（mapper 带 `reported_floor is null`）：完工那一刻的快照，
        //    之后谁都不许悄悄改写发钱的依据；真要改走人工调整（ADJUST）留痕。
        // ⚠️ 它**不影响向客户收的楼层费**（那是下单时按地址快照的 orders.floor_fee）。
        Integer reportedFloor = intOrNull(params == null ? null : params.get("reportedFloor"));
        if (reportedFloor != null) {
            if (reportedFloor <= 0 || reportedFloor > 200) {
                throw new BusinessException("楼层数不合理（请填 1~200，没有楼层就不用填）");
            }
            orderMapper.saveReportedFloor(orderId, reportedFloor);
        }

        // 解析 itemReturns 数组（按商品核对回桶）
        // 【不信任客户端】商品维度一律由后端用 orderItemId 反查 order_item.product_id 得到，
        // 客户端只提供 orderItemId 与数量。productName 仅用于生成差异说明文案。
        int returnBucketQty = 0;
        List<Map<String, Object>> itemReturns = null;
        Map<Long, Integer> returnedByProduct = new HashMap<>();
        List<OrderItem> orderItems = orderItemMapper.listByOrderId(orderId);
        Map<Long, Long> itemIdToProductId = new HashMap<>();
        if (orderItems != null) {
            for (OrderItem oi : orderItems) {
                if (oi.getId() != null && oi.getProductId() != null) {
                    itemIdToProductId.put(oi.getId(), oi.getProductId());
                }
            }
        }
        if (!isFirstBarrelOrder) {
            if (params != null && params.containsKey("itemReturns")) {
                Object ir = params.get("itemReturns");
                if (ir instanceof List) {
                    itemReturns = normalizeReturnInputs(order, (List<?>) ir, orderItems);
                    for (Map<String, Object> item : itemReturns) {
                        Object actual = item.get("actual");
                        if (actual instanceof Number) {
                            int act = ((Number) actual).intValue();
                            try {
                                returnBucketQty = Math.addExact(returnBucketQty, act);
                            } catch (ArithmeticException overflow) {
                                throw new BusinessException("回收空桶总数超出有效范围");
                            }
                            Object oiId = item.get("orderItemId");
                            Long pid = null;
                            if (oiId instanceof Number) {
                                pid = itemIdToProductId.get(((Number) oiId).longValue());
                            }
                            // 单商品订单容错：即使前端没带 orderItemId 也能归位
                            if (pid == null && itemIdToProductId.size() == 1) {
                                pid = itemIdToProductId.values().iterator().next();
                            }
                            if (pid == null) {
                                throw new BusinessException("回桶明细缺少有效的商品信息，请更新小程序后重试");
                            }
                            returnedByProduct.merge(pid, act, Integer::sum);
                        }
                    }
                } else {
                    throw new BusinessException("回桶明细格式不正确，请重新填写");
                }
            }
            // 兼容旧版 returnBucketQty 参数（无商品维度，按订单明细数量比例分摊）
            if (itemReturns == null && params != null && params.containsKey("returnBucketQty")) {
                Object rb = params.get("returnBucketQty");
                if (rb instanceof Number) {
                    returnBucketQty = returnQuantity(rb, "回收空桶数");
                    returnedByProduct = splitByItemRatio(orderItems, returnBucketQty);
                }
            }
        }
        if (returnBucketQty < 0) {
            throw new BusinessException("回收空桶数不能为负数");
        }
        // 2026-10-05：旧必填/补齐闸门阻断合法送达；C-02 已定原因选填。
        // 只校验已填事实，未知差额不伪造为“其他”；仍在桶账写入前拒绝非法数量/原因。
        assertReturnReasonsMatchGap(itemReturns);

        // 2026-10-03：现场现金先实收、激活本单容量，再交桶；后续失败整笔事务回滚。
        if(orderBarrelPurchases.hasPurchase(orderId) && Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod())
                && !paymentService.hasPaidRecord(orderId)) {
            if(params==null || !Boolean.TRUE.equals(params.get("collected")))throw new BusinessException("请先收齐水款和本单押金，再确认交桶");
            if(!java.util.Objects.equals(StationUtil.settleStation(order),AuthContext.getStationId()))throw new BusinessException("仅结算站可以确认现场收款");
            paymentService.recordCashCollection(orderId);
            paymentService.applyDepositOnPaid(orderId);
        }
        // ===== 桶账（全系统唯一写入口）=====
        // newOver = oldOver + (delivered − returned) − rightPurchase，按商品结算；
        // 唯一校验是【物理上限】returned <= 占用_before(权益+over)，over 允许为负（多还桶/水站暂存）。
        BarrelLedgerService.DeliveryOutcome ledgerOutcome =
                barrelLedgerService.applyDelivery(orderId, order.getCustomerId(), order.getStationId(),
                        returnedByProduct, staffId);
        int owed = ledgerOutcome.totalOverDelta();
        if(barrelLedgerService.independentOrder(orderId)) {
            // 正常领取已付押金的桶只改变实物，不是欠桶，也不应触发差异异常。
            owed-=barrelLedgerService.orderRights(orderId).stream().mapToInt(com.example.aquaflow.entity.BarrelRightReservation::getPickupQty).sum();
        }

        // 物理桶流水留痕（type=8 配送收发明细）：让对账能用流水反推占用，与「权益 + over」交叉验证
        recordDeliveryBarrels(order, ledgerOutcome, staffId);

        // 从 itemReturns 构建差异说明
        String discrepancyNote = null;
        if (itemReturns != null) {
            StringBuilder noteSb = new StringBuilder();
            for (Map<String, Object> item : itemReturns) {
                String productName = item.get("productName") != null ? item.get("productName").toString() : "";
                Object expObj = item.get("expected");
                Object actObj = item.get("actual");
                int exp = expObj instanceof Number ? ((Number) expObj).intValue() : 0;
                int act = actObj instanceof Number ? ((Number) actObj).intValue() : 0;
                int disc = exp - act;
                if (disc > 0) {
                    noteSb.append(productName).append("少").append(disc).append("桶");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> reasons = (List<Map<String, Object>>) item.get("reasons");
                    if (reasons != null && !reasons.isEmpty()) {
                        noteSb.append("(");
                        for (int i = 0; i < reasons.size(); i++) {
                            Map<String, Object> r = reasons.get(i);
                            String key = r.get("key").toString();
                            Object qtyObj = r.get("qty");
                            int qty = qtyObj instanceof Number ? ((Number) qtyObj).intValue() : 0;
                            String label = switch (key) {
                                case "customer_kept" -> "客户留存";
                                case "lost" -> "路上丢失";
                                case "damaged" -> "破损";
                                case "wrong" -> "送错";
                                default -> "其他";
                            };
                            if (i > 0) noteSb.append(",");
                            noteSb.append(label).append("×").append(qty);
                        }
                        noteSb.append(")");
                    }
                    noteSb.append(";");
                }
            }
            if (noteSb.length() > 0) discrepancyNote = noteSb.toString();
        } else if (params != null && params.containsKey("barrelDiscrepancyNote")) {
            discrepancyNote = (String) params.get("barrelDiscrepancyNote");
        }

        // ===== 支付前置校验（[AQ-002][AQ-007]）=====
        // 原则：PAID 只能由支付链路（createPayment / confirmPayment / 现金现场收款）写入，
        // 配送员点"完成"绝不能是付款动作。否则未付款的微信单 / 未扣票的水票单会被白送。
        Integer pm = order.getPaymentMethod();
        boolean paid = paymentService.hasPaidRecord(orderId);
        boolean isCashOnDelivery = pm == null || Integer.valueOf(PayMethod.CASH).equals(pm);
        boolean collected = params != null && Boolean.TRUE.equals(params.get("collected"));

        // 微信(1) / 水票(3)：必须有 PAID 流水才能完成，否则直接拒绝。
        // 注意此处抛异常而非 return：applyDelivery 已经改过桶账，必须整笔回滚。
        if (!isCashOnDelivery && !paid) {
            throw new BusinessException("该订单尚未完成支付，请先完成支付再配送（微信需支付回调，水票需先扣减）");
        }

        // ===== [2026-09-25 库存预留模型] 出库：**完成配送**才是实物离开仓库的那一刻 =====
        // 位置刻意放在状态 CAS **之前**：预留不足（缺货待补没补上）或本站实物不够时直接拒绝，
        // 让站长先去入库补足 —— 绝不允许"少扣一点先把单结了"（问题 4b 的病根就是那 7 桶永不落账）。
        // 站别必须是**履约站**（跨站外派后 = 接单站），不是订单的归属站：货当初记在哪个站、这次就从哪个站出。
        // 放在这里也顺带保证：本方法前半段对桶账/工钱做的事，在这一步失败时一并回滚。
        // 站别传"当前履约站"（`StationUtil.deliveryStation` = `coalesce(delivery_station_id, station_id)`）：
        // shipForOrder 会拿它逐条核对凭据站别，凭据挂错站（例如换站那步没搬凭据）是**明确失败**，
        // 不是"扣另一站的货"（返工 V10）。
        inventoryReservationService.shipForOrder(orderId, StationUtil.deliveryStation(order));

        // 处理桶差异异常录入
        Long exceptionId = null;
        if (owed != 0) {
            OrderBarrelExceptionService.ReturnInput input = new OrderBarrelExceptionService.ReturnInput();
            input.setActualReturn(returnBucketQty);
            input.setStaffAction(owed > 0 ? "PARTIAL" : "FULL");
            input.setStaffNote(discrepancyNote);
            // ⚠️ [2026-09-25 架构评审问题 9] 这里**原来包着 try/catch，catch 里只 log.error 后继续**。
            // 为什么那是错的：recordReturn 是 @Transactional 方法、经接口代理调用 ⇒ 它**参与同一个事务**；
            // 一旦它抛异常，Spring 已经把共享事务标记成 rollback-only，外层 catch 解除不了 ——
            // 后面的语句照跑，最终 commit 抛 UnexpectedRollbackException，用户看到的是"系统错误"，
            // 而且日志里那条"录入回桶异常失败"并不是真正的原因（真正的原因是事务已被判死）。
            // 判据（AGENTS §6）：**不要在被 @Transactional 的方法里 catch 业务异常**；
            // PaymentServiceImpl 里处理唯一键冲突那段就是正确写法（必须抛出）。
            //
            // 正确做法：异常单是**本次业务事实**（缺桶/多桶要留痕、要站长处置），写不成就是这一单
            // 没完成 —— 整笔回滚，异常照常往上抛：
            //   · recordReturn 自己抛的 BusinessException（带可读文案）→ GlobalExceptionHandler 兜成 code=1；
            //   · 真正意外的异常 → 500 + SYSTEM 告警（这正是它该有的分类，不要吞掉）。
            // 可以延后到"提交之后再做"的只有外部通知一类动作，不是账务事实。
            OrderBarrelExceptionService.OrderBarrelExceptionDTO ex =
                    orderBarrelExceptionService.recordReturn(orderId, input);
            exceptionId = ex.getId();
        }

        // ===== 决定最终状态（付款动作只能由支付链路写）=====
        int finalStatus;
        boolean markPaid = false;
        if (isCashOnDelivery) {
            if (paid) {
                // 已收款（例：站长已确认），直接完成；collected 不再影响付款状态
                finalStatus = OrderStatus.COMPLETED;
                markPaid = true;
            } else if (collected) {
                // [2026-09-18 修订 AQ-043] 跨站外派单的收款判权：**认结算站**，不再认归属站。
                // 旧口径（"跨站单仅原归属站可确认收款"）成立的年代跨站单的钱记归属站，钱与欠桶账同站；
                // 三站语义（v47 `orders.settle_station_id`）之后本单营收（水费 + 配送费 + 楼层费）归
                // **结算站** = 抢单/定向外派成功后的履约站 —— 谁送谁收钱谁确认，继续要求归属站确认，
                // 等于让一个不拿这笔钱的人去点"已收款"（他连这单都点不进来：本方法开头的
                // checkStationOwnership 只放行履约站）。
                // ⚠️ 只改这一个判权点：`recordCashCollection` 的站别、押金入账（applyDepositOnPaid）、
                // 欠桶录入一律不动 —— 押金 / 水票 / 桶权益是"客户买在哪个站的资产"，仍记**归属站**。
                Long settleStation = StationUtil.settleStation(order);
                Long myStation = AuthContext.getStationId();
                if (settleStation != null && (myStation == null || !myStation.equals(settleStation))) {
                    throw new BusinessException("跨站外派订单需由实际配送站（结算站）确认收款，请由该站操作");
                }
                finalStatus = OrderStatus.COMPLETED;
                markPaid = true;
                paymentService.recordCashCollection(orderId);   // 补写流水，保证账证一致
            } else {
                finalStatus = OrderStatus.DELIVERED;
            }
        } else {
            // 微信 / 水票：到此必 paid == true（已在上面拦截），正常完成
            finalStatus = OrderStatus.COMPLETED;
            markPaid = true;
        }

        // 状态 CAS：配送中 → 最终状态
        int statusAffected = orderMapper.updateStatusIf(orderId, OrderStatus.DELIVERING, finalStatus);
        if (statusAffected == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }

        // 支付状态：[2026-09-16 修正] 未收款时**保持原状态**，绝不倒回 未支付(0)。
        // 现金单下单即 待收款(1)，"送到门口、钱还没收"本身就是 待收款——这正是
        // DashboardMapper 的待收款金额口径（pendingAmount = payment_status=1 且未取消）。
        // 此前写成 UNPAID(0)，会让这笔应收从站长「待收款」合计里消失（看不到该催谁）。
        // 状态只前进：只有真的收到钱才写 已付款(2)（见 OrderMapper.markPaidIfCollectable）。
        int payCur = order.getPaymentStatus() != null ? order.getPaymentStatus() : PaymentStatus.UNPAID;
        if (markPaid && payCur != PaymentStatus.PAID) {
            // [2026-09-30 修 F-39] 两件事，都不许省：
            //   ① **终态不能被当成"可收款"**：3(已退款) / 4(已取消) 的单如果继续走「送达 + 记收款」，
            //      后面会照常 `applyDepositOnPaid` 入押金、并产生**计件工钱** —— 等于给一张已经退过钱
            //      或已取消的单发工资。这里**失败出声**（抛业务错误 ⇒ 整笔回滚），不要静默继续。
            //   ② **0 行不一定错**：`payCur ∈ {0,1}` 时 CAS 命中 0 行只可能来自"并发的另一条收款路径
            //      刚把它置为 已付(2)"（`refundPayment` 要求原状态是 PAID，不可能把 0/1 改成 3），
            //      那是良性竞态，继续往下走是对的 ⇒ 所以这里**不**把返回值当错误。
            //   ⚠️ **也不要用 `getById` 回读来判断**：REPEATABLE READ 下普通 SELECT 读到的是本事务
            //      开始时的快照（就是上面的 `order`），会把良性竞态误判成异常；要当前读就得 `FOR UPDATE`，
            //      为这点事给订单行加锁不值 —— 用快照里的 `payCur` 判 ① 已经足够。
            if (payCur == PaymentStatus.REFUNDED || payCur == PaymentStatus.CANCELLED) {
                throw new BusinessException("该订单支付状态为「" + PaymentStatus.textOf(payCur)
                        + "」，不能按「已收款」完成配送，请刷新后重试");
            }
            orderMapper.markPaidIfCollectable(orderId);
        }
        // [AQ-009] 只要订单最终为已付款，就在此刻入账预收桶押金（幂等，重复调用安全）
        if (markPaid) {
            paymentService.applyDepositOnPaid(orderId);
        }

        // 纯数据字段回写（回桶数 / 欠桶数 / 差异说明 / 异常单号），不含状态
        orderMapper.updateDeliveryOutcome(orderId, returnBucketQty, owed, discrepancyNote, exceptionId);
        dispatchAgreements.delivered(order,ledgerOutcome);

        // [v37] 配送员计件工资：状态 CAS 已成功 → 这一单确实送达了，这才产生工钱。
        // 幂等由 uk_earning_auto（生成列唯一键）兜底，重复完成配送会被拦。
        // ⚠️ 工钱**不进客户对账**（那些等式是客户/资产维度），它走独立等式 E-PAY —— 
        // 混进去会让每天 03:00 的日结必然报不平、淹没真问题。
        staffEarningService.recordDeliveryEarnings(orderId);

        // [2026-09-27] 订单一旦离开"可取消"状态（1 待配送 / 2 配送中），还挂着的**客户取消申请**
        // 就再也没有出口了：`approveCancelRequest` 用的是同一道 `OrderStatus.isCancellable` 门槛，
        // 必然拒掉它 —— 而申请行仍是 PENDING，于是它**永远留在站长的 P0「客户取消申请」待办里**
        // （实测：order 27 就是这么挂了 7 天，站长点"同意"只会看到"订单已完成，不能再取消"）。
        // 所以在这里如实收尾：**驳回**（不是 CANCELLED —— 撤回是发起方的动作，
        // 这里发生的事实是"申请没能生效，水已经送出去了"）。
        // [v78] 只对当前 CUSTOMER/CANCEL_REQUEST 凭据做 CAS，并同事务保存客户可见结果。
        // STAFF 转单 / 退回 / 站间指定退回各有自己的决策点，不做类别批量清除。
        OrderTransfer staleCancel=orderTransferMapper.findPendingByOrderAndKind(orderId,OrderTransfer.KIND_CUSTOMER);
        if (staleCancel!=null && OrderTransfer.SUB_CANCEL_REQUEST.equals(staleCancel.getSubKind())) {
            if(orderTransferMapper.resolvePendingRequest(staleCancel.getId(),OrderTransfer.KIND_CUSTOMER,OrderTransfer.SUB_CANCEL_REQUEST,OrderTransfer.STATUS_REJECTED,staffId)!=1)
                throw new BusinessException("取消申请状态已变化，请刷新订单后重试");
            if(cancelResults.insert(staleCancel.getId(),"订单已完成配送，本次取消申请未生效；如有异议可联系水站。",true,staffId)!=1)
                throw new BusinessException("取消申请处理结果未保存");
            log("CANCEL_REQUEST_AUTO_REJECTED", orderId,
                    serviceMap("reason", "订单已完成配送，取消申请自动关闭"));
        }

        // Delivery wins the same order-row lock as handoff: the old owner delivered,
        // so the target cannot subsequently take a terminal order. Other STAFF requests
        // retain their dedicated decisions; customer cancellation above stays unchanged.
        OrderTransfer staleHandoff = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_STAFF);
        if (staleHandoff != null && OrderTransfer.SUB_TRANSFER.equals(staleHandoff.getSubKind())) {
            int closed = orderTransferMapper.resolvePendingRequest(staleHandoff.getId(), OrderTransfer.KIND_STAFF,
                    OrderTransfer.SUB_TRANSFER, OrderTransfer.STATUS_REJECTED, staffId);
            if (closed > 0) log("TRANSFER_AUTO_CLOSED", orderId,
                    serviceMap("reason", "原配送员已完成配送，转让失效"));
        }

        if (params != null && params.containsKey("note")) {
            orderMapper.appendSpecialNote(orderId, "[配送备注] " + params.get("note"));
        }

        Map<String, Object> d = new HashMap<>();
        d.put("returnBucketQty", returnBucketQty);
        d.put("barrelDiscrepancy", owed);
        d.put("isCashOnDelivery", isCashOnDelivery);
        d.put("collected", collected);
        d.put("finalStatus", finalStatus);
        log("COMPLETE", orderId, d);
    }

    /**
     * 校验选填的现场原因，不要求覆盖全部少回差额。见 design/16 C-02。
     * <p>未知原因不能默认为“其他”；已填数量按合计校验，不能通过伪造 expected 扩大额度。</p>
     */
    private void assertReturnReasonsMatchGap(List<Map<String, Object>> itemReturns) {
        if (itemReturns == null || itemReturns.isEmpty()) {
            return;
        }
        for (Map<String, Object> item : itemReturns) {
            String productName = item.get("productName") != null ? item.get("productName").toString() : "该商品";
            int exp = item.get("expected") instanceof Number n ? n.intValue() : 0;
            int act = item.get("actual") instanceof Number n ? n.intValue() : 0;
            int gap = exp - act;
            // 旧客户端会为新领桶上传按送出数填的原因；无真实少回差额时不保存这类旧提示。
            if (gap <= 0) continue;
            long reasonSum = 0;
            Object reasonsObj = item.get("reasons");
            if (reasonsObj == null) continue;
            if (reasonsObj instanceof List<?> reasons) {
                for (Object o : reasons) {
                    if (!(o instanceof Map<?, ?> reason)) {
                        throw new BusinessException("「" + productName + "」的原因格式不正确");
                    }
                    Object key = reason.get("key");
                    if (!(key instanceof String) || !Set.of("customer_kept", "lost", "damaged", "wrong", "other").contains(key)) {
                        throw new BusinessException("「" + productName + "」的少桶原因无效，请重新选择");
                    }
                    Object qtyObj = reason.get("qty");
                    int qty = returnQuantity(qtyObj, "「" + productName + "」的少桶原因数量");
                    if (qty == 0) {
                        throw new BusinessException("已填少桶原因数量必须为正整数，不填原因可直接移除");
                    }
                    reasonSum += qty;
                }
            } else {
                throw new BusinessException("「" + productName + "」的原因格式不正确");
            }
            if (reasonSum > Math.max(0, gap)) {
                throw new BusinessException("「" + productName + "」少 " + gap + " 桶，但少桶原因合计 "
                        + reasonSum + " 桶，不能超过实际差额，请重新填写原因");
            }
        }
    }

    /** 应回数复用唯一回桶计划；请求仅报告实回，不允许用 expected 改写现场差额。 */
    private List<Map<String, Object>> normalizeReturnInputs(Orders order, List<?> inputs, List<OrderItem> orderItems) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (inputs.isEmpty()) return normalized;
        List<BarrelService.ReturnPlanItem> plan = barrelService.returnPlanOfOrder(
                order.getId(), order.getCustomerId(), order.getStationId());
        Map<Long, BarrelService.ReturnPlanItem> byItem = new HashMap<>();
        if (plan != null) for (BarrelService.ReturnPlanItem row : plan) byItem.put(row.getOrderItemId(), row);
        Set<Long> knownItems = new java.util.HashSet<>();
        if (orderItems != null) for (OrderItem item : orderItems) knownItems.add(item.getId());
        Set<Long> reportedItems = new java.util.HashSet<>();
        for (Object input : inputs) {
            if (!(input instanceof Map<?, ?> raw)) throw new BusinessException("回桶明细格式不正确");
            Long itemId = null;
            if (raw.get("orderItemId") instanceof Number n) {
                try { itemId = new BigDecimal(n.toString()).longValueExact(); }
                catch (NumberFormatException | ArithmeticException invalid) {
                    throw new BusinessException("回桶明细缺少有效的商品信息");
                }
            } else if (raw.get("orderItemId") == null && byItem.size() == 1) {
                itemId = byItem.keySet().iterator().next();
            } else if (raw.get("orderItemId") == null && knownItems.size() == 1) {
                itemId = knownItems.iterator().next();
            }
            BarrelService.ReturnPlanItem row = byItem.get(itemId);
            int actual = returnQuantity(raw.get("actual"), "回收空桶数");
            if (row == null && knownItems.contains(itemId)) {
                // 历史客户端可能把非桶装商品的零回桶一并上传；忽略零行，不生成假的桶差额。
                if (actual == 0) continue;
                throw new BusinessException("该商品不涉及回桶，请核对实际商品");
            }
            if (row == null || row.getSuggestedQty() == null || !reportedItems.add(itemId)) {
                throw new BusinessException("回桶明细的商品无效或重复，请重新核对");
            }
            Map<String, Object> item = new HashMap<>();
            item.put("orderItemId", itemId);
            item.put("productName", raw.get("productName"));
            item.put("expected", row.getSuggestedQty());
            item.put("actual", actual);
            item.put("reasons", raw.get("reasons"));
            normalized.add(item);
        }
        return normalized;
    }

    /** 不截断小数或溢出值；数量转换错误须在桶账写入前作为业务拒绝。 */
    private int returnQuantity(Object value, String label) {
        if (value instanceof Number number) {
            try {
                int quantity = new BigDecimal(number.toString()).intValueExact();
                if (quantity >= 0) return quantity;
            } catch (NumberFormatException | ArithmeticException invalid) {
                // 下方统一给可读业务错误，不让非法请求变成系统故障。
            }
        }
        throw new BusinessException(label + "必须为非负整数，且不能超出有效范围");
    }

    /**
     * 旧客户端只回传一个总回桶数、没有商品维度时，按订单明细数量比例分摊（余数补给最后一项）。
     * 这是兼容兜底，正常路径（itemReturns 带 orderItemId）不会走到这里。
     */
    private Map<Long, Integer> splitByItemRatio(List<OrderItem> items, int total) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty() || total <= 0) return result;
        int sumQty = 0;
        for (OrderItem oi : items) sumQty += (oi.getQuantity() == null ? 0 : oi.getQuantity());
        if (sumQty <= 0) return result;
        int assigned = 0;
        Long lastPid = null;
        for (OrderItem oi : items) {
            if (oi.getProductId() == null) continue;
            lastPid = oi.getProductId();
            int q = (oi.getQuantity() == null ? 0 : oi.getQuantity());
            int v = (int) Math.floor((double) total * q / sumQty);
            result.merge(oi.getProductId(), v, Integer::sum);
            assigned += v;
        }
        if (lastPid != null && assigned < total) {
            result.merge(lastPid, total - assigned, Integer::sum);
        }
        return result;
    }

    /**
     * 把本次配送的<b>物理桶收发</b>写进 barrel_record（type=8），每个商品一行。
     *
     * <p>为什么必须写：权益账（lot / over）自洽只能证明"账做得平"，证明不了"顾客手上真有这么多桶"。
     * 只有留下送出/收回的流水，对账的 E5 才能用流水反推占用，跟「权益 + over」交叉验证——
     * 这是发现"桶实际丢了但账上还在"的唯一办法。</p>
     */
    private void recordDeliveryBarrels(Orders order, BarrelLedgerService.DeliveryOutcome outcome, Long staffId) {
        if (outcome == null || outcome.getLines() == null) return;
        Long customerId = order.getCustomerId();
        Long stationId = order.getStationId();
        if (customerId == null || stationId == null) return;

        for (BarrelLedgerService.DeliveryOutcome.Line line : outcome.getLines()) {
            int delivered = line.getDelivered() == null ? 0 : line.getDelivered();
            int returned = line.getReturned() == null ? 0 : line.getReturned();
            int purchased = line.getRightPurchase() == null ? 0 : line.getRightPurchase();
            if (delivered == 0 && returned == 0 && purchased == 0) continue;

            BarrelRecord r = new BarrelRecord();
            r.setCustomerId(customerId);
            r.setStationId(stationId);
            r.setProductId(line.getProductId());
            r.setType(8); // 配送收发明细
            r.setQuantity(delivered);
            r.setDeliveredQty(delivered);
            r.setReturnedQty(returned);
            r.setRelatedOrderId(order.getId());
            r.setStatus(3); // 即时生效，不参与退桶审批
            r.setOverBefore(line.getOverBefore());
            r.setOverAfter(line.getOverAfter());
            r.setDepositRefund(BigDecimal.ZERO); // 配送不涉及退款
            r.setNote("送出 " + delivered + " / 收回 " + returned + " / 本单新购权益 " + purchased);
            r.setOperatorId(staffId);
            r.setCreateTime(LocalDateTime.now());
            barrelRecordMapper.insert(r);
        }
    }

    /* ==================================================================
     *  取消 / 拒单（统一编排）
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectOrder(Long orderId, String reason) {
        Orders order = requireOrderForUpdate(orderId);
        // 配送员与站长都必须校验订单归属，杜绝越权取消任意订单并触发退款
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("无权操作他站订单");
        }
        if (AuthContext.isDelivery()) checkDeliverySelf(order);
        // [2026-09-13] 状态门槛：此前这里只校验归属、不校验状态，
        // 于是「配送员点完成」与「配送员/站长点拒单」在两个客户端上没有任何互斥，
        // 且已完成的订单也能被拒单退款。业务上只允许 待配送/配送中 拒单
        // （[2026-09-21] 已送达也被排除：货已交付，异常改走「配送异常」）。
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException(OrderStatus.notCancellableReason(cur, "拒单"));
        }
        // [2026-09-14] 配送员对「已接单」订单不得直接拒单（那等于绕开站长取消了订单并触发退款），
        // 必须提交取消申请由站长审批。站长本人不受限——他就是要点头的那个人。
        // 待配送(1) 尚未接单，配送员仍可直接拒单。
        if (AuthContext.isDelivery() && cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单已被接单，取消需经站长同意，请提交取消申请");
        }
        String r = (reason != null && !reason.isBlank()) ? reason : "水站拒单";
        cancelWithRefund(orderId, r, "[拒单] " + r);
        notifyCustomerRejected(order.getCustomerId(), orderId, r);
        log("REJECT", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveOrder(Long orderId, String reason) {
        Orders order = requireOrderForUpdate(orderId);
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
        if (AuthContext.isDelivery()) checkDeliverySelf(order);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("拒单原因必填");
        }
        // [2026-09-13] 与 rejectOrder 同款状态门槛：已完成/已取消的订单不允许再走退款
        // （[2026-09-21] 已送达同样被排除）
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException(OrderStatus.notCancellableReason(cur, "解决/拒单"));
        }
        // [2026-09-14] 与 rejectOrder 同款收口：配送员对已接单订单不得直接取消（会触发退款），
        // 必须提交取消申请由站长审批。站长本人不受限。
        if (AuthContext.isDelivery() && cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单已被接单，取消需经站长同意，请提交取消申请");
        }
        // 注意：不要提前置 CANCELLED，否则 refundOrder 的状态门槛(orderStatus < DELIVERED)会失效、
        // 导致押金不退/配送中桶悬挂。状态由 refundOrder 末尾统一置位。
        cancelWithRefund(orderId, reason, "[解决/拒单] " + reason);
        log("RESOLVE", orderId, serviceMap("reason", reason, "refundProcessed", true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stationReject(Long orderId, String reason, boolean tryDispatch) {
        Orders order = requireOrderForUpdate(orderId);
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        String r = reason != null ? reason : "站长拒单";

        if (tryDispatch) {
            int cur = order.getStatus() != null ? order.getStatus() : 0;
            // [2026-09-21 补] 外派分支此前**没有状态门槛**，而下面的 CAS 是
            // `outsourceToPoolIf(id, PENDING, cur)` —— 即 `set status=1 where status=cur`。
            // 于是对一张**已送达(3)** 的单调用本端点，会把状态倒滚成 待配送(1)：
            // 货已经交付了却又变回"待分配"，违反「状态只前进、不许倒滚」（AGENTS.md §8.18），
            // 而且会把它重新推进抢单池、让别站去送一张已经送过的单。
            // 入池/退回重派的语义与取消同源（都是"这单本站不送了"），故共用 isCancellable。
            if (!OrderStatus.isCancellable(cur)) {
                throw new BusinessException(OrderStatus.notCancellableReason(cur, "拒单外派"));
            }
            // [2026-09-18] 这是"放进抢单池"的**第二个入口**（第一个是 outsource(targetStationId=null)），
            // 押金/桶权益的闸门必须同样装上，否则换个入口就绕过去了。
            if (involvesDepositOrBarrelRights(order)) {
                throw new BusinessException("该单涉及押金/桶权益，不能放入抢单池（本次拒单也未执行）："
                        + DEPOSIT_BARREL_RISK_TEXT);
            }
            // 原子：清空履约站与配送员、状态回到待配送（原实现是先 update 再 clearDispatchStation 两次写）
            int changed = orderMapper.outsourceToPoolIf(orderId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            dispatchAgreements.recallIfUnstarted(orderId);
            dispatchAgreements.prepare(order, null);
            // 退回池 = 这单又回归属站 → 待收款流水跟着回归属站
            movePendingCollectionTo(orderId, order.getStationId());
            // [2026-09-25 库存预留模型] 库存凭据跟着履约站走：池中的单没人履约 ⇒ 预留回**归属站**
            // （若不搬，这单的货就会一直挂在上一个站名下，取消时释放到错站 —— 问题 4a 的形状）
            inventoryReservationService.transferForOrder(orderId, order.getStationId());
            orderMapper.appendSpecialNote(orderId,
                    DispatchKind.NOTE_POOL_BY_REJECT + "，原因=" + r + "，原归属站=" + stationId);
        } else {
            // [2026-09-13] 取消分支原先没有任何状态门槛（外派分支靠 outsourceToPoolIf 的 CAS 兜着）。
            // ⚠️ 与本类其它取消入口共用**同一道闸门** `OrderStatus.isCancellable`（= 待配送/配送中）。
            //    原注释写的"只允许 待配送/配送中/已送达"是 2026-09-21 之前的口径，已送达不再可取消。
            //    原实现还自己拼了一句"该订单当前状态不可取消（status=N）"—— 与全仓统一文案分叉，
            //    已送达的单会收到一句**不说下一步去哪**的话（找不到「配送异常」这条出路）。
            int curCancel = order.getStatus() != null ? order.getStatus() : 0;
            if (!OrderStatus.isCancellable(curCancel)) {
                throw new BusinessException(OrderStatus.notCancellableReason(curCancel, "拒单"));
            }
            cancelWithRefund(orderId, r, "[拒单] " + r);
            notifyCustomerRejected(order.getCustomerId(), orderId, r);
        }
        log("STATION_REJECT", orderId, serviceMap("reason", r, "tryDispatch", tryDispatch));
    }

    /* ==================================================================
     *  派单 / 外派 / 召回 / 抢单
     * ================================================================== */

    /**
     * 涉及押金 / 桶权益的单，跨站外派前的**风险提示与出路** —— 全仓唯一文案来源。
     *
     * <p>产品口径（2026-09-18）：「如果产生押金问题，特别提醒站长，一般建议禁止外派直接拒单，
     * 因为押金不好划定；要么就是水站间的欠桶问题。如果不拒单也只能<b>指定水站外派</b>，
     * 双方都特别提醒后<b>同意</b>才行。」本文案同时用于三处：① 拒绝放入抢单池 / 拒绝抢单的错误文案；
     * ② 定向外派与接收确认下发给前端的提示文案（见 {@link #crossStationRiskNote}）；
     * ③ 两侧确认后写进 {@code orders.special_note} 的留痕。**前端不得自编同义文案**
     * （AGENTS §6：口径文案只有一个来源）。</p>
     */
    public static final String DEPOSIT_BARREL_RISK_TEXT =
            "该单涉及押金/桶权益，跨站结算口径不清（押金记归属站、回桶也记回归属站的桶账，"
                    + "水站之间的欠桶在系统里无处登记）。建议直接拒单；如确需外派，请改用定向外派，"
                    + "并由外派方与接收站双方确认风险后共同承担。";

    /**
     * 结算站变了 → 这张单**尚未确认**的待收款流水一起搬过去（谁结算谁催收，v47 2026-09-18）。
     *
     * <p>流水的站别在"发起收款"那一刻就按当时的站写死了（{@code PaymentServiceImpl.createPayment}），
     * 订单随后被抢单 / 定向外派 / 退回池 / 召回时它不会自己跟着走 —— 结果是
     * 「履约站收了钱、凭据却挂在归属站」，两个站的「待确认收款」列表还会各错一边。</p>
     *
     * <p>⚠️ 必须在<b>站别 CAS 成功之后</b>调用（CAS 失败就直接抛异常回滚，不能先搬），
     * 并且与本类方法在同一个 {@code @Transactional} 里 —— 搬流水与改站必须同生共死。</p>
     */
    private void movePendingCollectionTo(Long orderId, Long settleStationId) {
        paymentService.relocatePendingCollection(orderId, settleStationId);
    }

    /**
     * <b>跨站外派的风险判据</b>：本单是否涉及押金 / 桶权益。抢单池、定向外派、接收确认三处共用同一份判据。
     *
     * <p>取<b>并集</b>（宁可严一点也不要漏），两项分别对应产品点名的两类纠纷：</p>
     * <ol>
     *   <li>{@code orders.deposit_amount > 0} —— <b>本单要收押金</b>。押金账户按
     *       {@code (customer_id, station_id)} 记在<b>归属站</b>（{@code PaymentService.applyDepositOnPaid}
     *       → {@code customer_deposit_account}），跨站单却是履约站的人当场收钱／当场退桶，
     *       钱要记到归属站账上 —— 就是产品说的「押金不好划定」。</li>
     *   <li>{@code orders.delivery_bucket_qty > 0 且 orders.first_barrel_order = 0} ——
     *       <b>本单要送桶、且不是本站首笔买桶单</b>。首单免回桶核对（见 {@link #completeDelivery}
     *       的 isFirstBarrelOrder 分支），其余单完成配送时必须核对回桶；而回桶差量由
     *       {@code BarrelLedgerService.applyDelivery} 记在<b>归属站</b>
     *       （本类 completeDelivery 传的就是 {@code order.getStationId()}）——
     *       履约站司机手里的空桶在两站之间没有任何台账，即产品说的「水站间的欠桶问题」。</li>
     * </ol>
     *
     * <p><b>为什么第 2 项必须带上 {@code delivery_bucket_qty > 0}</b>：{@code first_barrel_order}
     * 的写入口径是 {@code OrderServiceImpl} 的
     * {@code totalNeededBuckets > 0 && !hasBarrelAsset(...)} —— <b>不含桶装水的单（瓶装水 / 饮水机）
     * 恒为 0</b>，只看它会把这类"根本碰不到桶"的普通单也一并拦死，违反产品
     * 「普通单保持原状、不要给所有外派加摩擦」。{@code delivery_bucket_qty} 同为下单时写死的快照
     * （无桶写 NULL/0，{@code OrderServiceImpl#setDeliveryBucketQty}），两列一起看才等价于"本单真的要动桶"。</p>
     *
     * <p>⚠️ 反过来说：<b>桶装水单几乎都会被判为风险单</b>（首单收押金 → 命中第 1 项；
     * 老客换水 → 命中第 2 项），这正是产品要的效果 —— 抢单池只剩"不碰桶"的单，
     * 桶装水单只能走定向外派 + 双方确认。放宽判据前先回去读那段产品原话。</p>
     */
    private boolean involvesDepositOrBarrelRights(Orders order) {
        if (order == null) return false;
        if (order.getDepositAmount() != null && order.getDepositAmount().signum() > 0) return true;
        boolean deliversBarrels = order.getDeliveryBucketQty() != null && order.getDeliveryBucketQty() > 0;
        // firstBarrelOrder 为 NULL（历史行）按"不是首单"处理 —— 偏严的一侧，宁可多拦不可漏
        return deliversBarrels && !Boolean.TRUE.equals(order.getFirstBarrelOrder());
    }

    @Override
    public String crossStationRiskNote(Orders order) {
        if(!involvesDepositOrBarrelRights(order))return null;
        if(!barrelLedgerService.independentOrder(order.getId()))return DEPOSIT_BARREL_RISK_TEXT;
        String note="客户押金、权益和桶账仍归原水站；本单服务、联系客户和消费收款由履约站负责。净送桶另有两站交接约定；历史桶损不自动判给履约站，争议须两站确认。";
        Map<String,Object> agreement=dispatchAgreements.info(order.getId());
        if(!agreement.isEmpty())note+="本单服务报酬 "+agreement.get("serviceAmount")+" 元；"+agreement.get("barrelNote")+"；桶折款 "+agreement.get("barrelAmount")+" 元。接收后报价固定。";
        return note;
    }

    /**
     * 外派方的显式确认留痕：谁（员工ID）在什么时候（{@code audit_log.create_time}）确认的，
     * 见 {@code audit_log} 的 {@code DISPATCH} / {@code OUTSOURCE_DIRECT}（detail 里带 {@code riskAcknowledged}）。
     *
     * <p>⚠️ 这里**不把整段风险文案抄进 special_note**：{@code orders.special_note} 是
     * {@code varchar(200)}，一张单上先后会追加 [外派] / [分配] / 本行，抄全文必超长——
     * 超长会以 {@code DataIntegrityViolationException} 抛在事务里（表现为
     * 「special_note值超出允许长度」），把一次正常的外派整个回滚掉（2026-09-18 实测踩到）。
     * 全文进 {@code audit_log.detail}（text 列，无此限制）。</p>
     */
    private void appendRiskAckNote(Long orderId, String role, Long stationId) {
        orderMapper.appendSpecialNote(orderId, "[外派风险确认] " + role + "=" + stationId
                + "，操作人=" + AuthContext.getUserId() + "，已确认押金/桶权益风险");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispatchExternal(Long orderId, Long targetStationId, String reason, boolean riskAcknowledged) {
        Long myStationId = AuthContext.getStationId();
        if (myStationId == null) throw new BusinessException("无法识别当前水站");

        Orders order = requireOrderForUpdate(orderId);
        requireDispatchRight(order, myStationId);
        requireNoPendingDirectedReturn(orderId);
        if (targetStationId == null) throw new BusinessException("targetStationId 不能为空");
        if (targetStationId.equals(myStationId)) throw new BusinessException("不能外派给自己水站");

        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("当前状态不可外派调度");
        }
        // [2026-09-18] 押金/桶权益单：定向外派前必须由**外派方显式确认**风险（产品：双方都特别提醒后同意）。
        // 校验放在任何写操作之前 —— 被拒时订单状态/履约站/备注一律不动。
        boolean risky = involvesDepositOrBarrelRights(order);
        if (risky && !riskAcknowledged) {
            throw new BusinessException("外派该单前必须先确认风险，本次操作未执行：" + DEPOSIT_BARREL_RISK_TEXT);
        }
        String r = reason != null ? reason : "外派配送";

        // [AQ-020] 仅修改 delivery_station_id、清空配送员；归属站保持不变。CAS 于状态。
        int dispatched = orderMapper.dispatchIfStatus(orderId, targetStationId, OrderStatus.PENDING);
        if (dispatched == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        dispatchAgreements.prepare(order,targetStationId);
        inventoryReservationService.transferForOrder(orderId,targetStationId);
        // 履约站 = 结算站 = 目标站 → 待收款流水跟着走
        movePendingCollectionTo(orderId, targetStationId);
        orderMapper.appendSpecialNote(orderId,
                DispatchKind.NOTE_DIRECT + myStationId + " 外派至 " + targetStationId + "，原因：" + r);
        if (risky) appendRiskAckNote(orderId, "外派方", myStationId);
        notifyCustomerTempDispatch(order.getCustomerId(), orderId, targetStationId);
        log("DISPATCH", orderId,
                serviceMap("orderId", orderId, "fromStationId", myStationId, "toStationId", targetStationId,
                        "reason", r, "depositBarrelRisk", risky, "riskAcknowledged", riskAcknowledged,
                        "riskNote", risky ? DEPOSIT_BARREL_RISK_TEXT : null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outsource(Long orderId, Long targetStationId, String reason, boolean riskAcknowledged) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        // 与 dispatchExternal 共用同一道判权：放池 / 退回池 / 定向外派都是"调度"，
        // 都适用「还没被接单之前这单仍算归属站的」（见 requireDispatchRight）。
        requireDispatchRight(order, stationId);
        requireNoPendingDirectedReturn(orderId);
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("当前状态不可外派");
        }
        boolean risky = involvesDepositOrBarrelRights(order);

        if (targetStationId != null) {
            if (targetStationId.equals(stationId)) throw new BusinessException("不能外派给自己水站");
            // 与 dispatchExternal 同一道闸门：两个端点是同一件事的两个入口，只堵一个等于没堵
            if (risky && !riskAcknowledged) {
                throw new BusinessException("外派该单前必须先确认风险，本次操作未执行：" + DEPOSIT_BARREL_RISK_TEXT);
            }
            String r = reason != null ? reason : "站长指定水站外派";
            int changed = orderMapper.outsourceToStationIf(orderId, targetStationId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            dispatchAgreements.recallIfUnstarted(orderId);
            dispatchAgreements.prepare(order,targetStationId);
            // 定向外派 = 钱货都归目标站 → 待收款流水跟着走
            movePendingCollectionTo(orderId, targetStationId);
            // [2026-09-25 库存预留模型] 库存凭据也跟着履约站走：旧站释放、新站按**新站可用量**重建。
            // 新站不够就是"到新站后仍缺货待补"（不阻断接单，保留"缺货可预订"）；
            // 但它完成配送时会被 shipForOrder 拦下（那 7 桶必须先在接单站入库补上）。
            inventoryReservationService.transferForOrder(orderId, targetStationId);
            orderMapper.appendSpecialNote(orderId,
                    DispatchKind.NOTE_DIRECTED + targetStationId + "，原因：" + r + "，原归属站=" + stationId);
            if (risky) appendRiskAckNote(orderId, "外派方", stationId);
            notifyCustomerTempDispatch(order.getCustomerId(), orderId, targetStationId);
            log("OUTSOURCE_DIRECT", orderId,
                    serviceMap("fromStationId", stationId, "toStationId", targetStationId, "reason", r,
                            "depositBarrelRisk", risky, "riskAcknowledged", riskAcknowledged,
                            "riskNote", risky ? DEPOSIT_BARREL_RISK_TEXT : null));
        } else {
            // [2026-09-18] 涉押金/桶权益的单**禁止入池**（不是"确认后可入"）：池子是跨租户可见面，
            // 认领方与归属站之间没有"双方同意"这一步，出了押金/欠桶纠纷连个确认人都找不到。
            if (risky) {
                throw new BusinessException("该单涉及押金/桶权益，不能放入抢单池：" + DEPOSIT_BARREL_RISK_TEXT);
            }
            int changed = orderMapper.outsourceToPoolIf(orderId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            dispatchAgreements.recallIfUnstarted(orderId);
            dispatchAgreements.prepare(order, null);
            // 放入池中 = 又回归属站 → 待收款流水跟着回归属站
            movePendingCollectionTo(orderId, order.getStationId());
            // [2026-09-25 库存预留模型] 同上：池中的单没人履约 ⇒ 预留回**归属站**
            inventoryReservationService.transferForOrder(orderId, order.getStationId());
            orderMapper.appendSpecialNote(orderId, DispatchKind.NOTE_POOL + "，原归属站=" + stationId);
            log("OUTSOURCE", orderId, serviceMap("stationId", stationId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelDispatch(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅能取消本站外派的订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        // [2026-09-22 产品裁定] **被接单之后这单就不归归属站管了，只能接单站管**
        //（原话：「外派出去的本单就不归本站管了，只能接单站管，联系等都是接单站执行」）。
        // 所以召回只允许在「还没被接单」时发起 —— 也就是状态仍是 待配送(1)：
        //   在抢单池里（delivery_station_id 为空）、已被指定给某站但对方还没接单，两种都还是 1；
        //   对方一接单，状态变 配送中(2)，本入口就必须关掉。
        // ⚠️ 原实现放行 配送中(2) 并把状态**改回** 待配送(1)。两个问题：
        //   ① 那是归属站把别站正在送的单抢回来，可能出现两个配送员送同一张单；
        //   ② 货已经在接单站的车上，"取消外派"根本拦不住这件事 —— 它只是把账改成归属站以为的样子。
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException(cur == OrderStatus.DELIVERING
                    ? "该单已被接单站接单，归接单站管理，本站不能再取消外派（请与接单站联系）"
                    : "该订单状态不可取消外派（当前 " + OrderStatus.textOf(cur) + "）");
        }
        if (stationId.equals(order.getDeliveryStationId())
                || (order.getDeliveryStationId() == null
                    && (order.getSpecialNote() == null || !order.getSpecialNote().contains("[外派]")))) {
            throw new BusinessException("该订单当前没有可召回的外派安排");
        }
        OrderTransfer returning = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_DIRECTED);
        if (returning != null) {
            returning = requirePendingDirectedReturn(order);
            // 旧版可能把已接单的主状态压成 1，不能借召回绕开履约站权限。
            if (directedReturnSourceStatus(order, returning) != OrderStatus.PENDING) {
                throw new BusinessException("该单已经接单，请由接单站处理或同意指定退回");
            }
            resolveDirectedReturn(returning, OrderTransfer.STATUS_CANCELLED);
            if (orderMapper.clearDirectedReturnMarkerIf(orderId, cur) != 1) {
                throw new BusinessException("订单状态已变化，请刷新后重试");
            }
        }
        // 召回为本站待分配（此时只可能是"在池中"或"已指定但对方未接单"两种）
        int changed = orderMapper.recallToStationIf(orderId, stationId, OrderStatus.PENDING, cur);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        // 召回 = 回归属站 → 待收款流水跟着回归属站
        dispatchAgreements.recallIfUnstarted(orderId);
        movePendingCollectionTo(orderId, stationId);
        // [2026-09-25 库存预留模型] 预留一并召回本站（否则这单的货还挂在被取消外派的那个站名下）
        inventoryReservationService.transferForOrder(orderId, stationId);
        orderMapper.appendSpecialNote(orderId, "[取消外派] 站长取消外派，恢复本站");
        log("CANCEL_DISPATCH", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimPool(Long orderId, Long targetStaffId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        // ⚠️ [2026-09-20] 原来先判 delivery_station_id、且文案写死"已被其他水站抢单"，
        // 但**下单时 delivery_station_id 就等于归属站**（OrderServiceImpl:479）—— 于是任何
        // "没被外派过"的单来抢（包括状态早已不是待配送的历史单）都会得到"被别站抢走了"这个
        // 与事实相反的解释，把排查引向错误方向（AGENTS §8.22：不能把失败说成事实）。
        // 现在按真实判据分两类，并且**先判状态** —— 状态不对时"在不在池里"根本不是重点。
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单当前状态不可抢单（仅" + OrderStatus.textOf(OrderStatus.PENDING)
                    + "的单可抢，当前为" + OrderStatus.textOf(cur) + "）");
        }
        if (order.getDeliveryStationId() != null) {
            // 履约站非空 = 这单**已经不在抢单池里**（池中的单该列应为 NULL）。
            // 至于为什么不在池里（从未外派 / 已被召回 / 已被别站接单），这里给不出唯一答案，
            // 那就**不猜**——把三种可能列出来，让站长去看「外派追踪」。
            throw new BusinessException("该订单不在抢单池中（可能未外派、已被召回或已被其他水站接单）");
        }
        if (targetStaffId == null) throw new BusinessException("请指定配送员");
        // [2026-09-18] 池子的**出口**也装同一道闸门：入池的两个入口都堵了，这里防的是"历史遗留在池中的
        // 押金/桶权益单"（规则上线前放进去的）。认领方看到提示后应让归属站召回（取消外派）自送，
        // 或由归属站改用定向外派 + 双方确认。
        if (involvesDepositOrBarrelRights(order)) {
            throw new BusinessException("该单涉及押金/桶权益，不能跨站抢单：" + DEPOSIT_BARREL_RISK_TEXT);
        }
        Staff target = requireDelivery(targetStaffId, stationId);

        // [AQ-020] 原子 CAS：仅当订单仍在池中（delivery_station_id 为空）且状态=待配送时才算抢到
        int grabbed = orderMapper.claimPoolIfFree(orderId, stationId, targetStaffId,
                OrderStatus.DELIVERING, OrderStatus.PENDING);
        if (grabbed == 0) {
            // ⚠️ 这一处文案**是对的**，别跟着上面那处一起改：CAS 影响 0 行 = 在读到 order 之后、
            // 执行 CAS 之前，池里这单被别站抢走（或状态已变）。这里本来就是"被抢走"的语义。
            throw new BusinessException("该订单已被其他水站抢单");
        }
        dispatchAgreements.accepted(order,stationId);
        // 抢单 = 钱货都归抢单站 → 待收款流水跟着走（否则归属站列着一笔它收不到的钱）
        movePendingCollectionTo(orderId, stationId);
        // [2026-09-25 库存预留模型] 库存凭据跟着抢单站走（旧站释放、本站在**本站可用量**内重建）。
        // 本站没货也能抢（保留"缺货可预订"），但完成配送时 shipForOrder 会要求先入库补足。
        inventoryReservationService.transferForOrder(orderId, stationId);
        orderMapper.appendSpecialNote(orderId, " [抢单] " + stationId + "站抢单成功，配送员=" + target.getName());
        notifyCustomerTempDispatch(order.getCustomerId(), orderId, stationId);
        log("CLAIM_POOL", orderId, serviceMap("stationId", stationId, "staffId", targetStaffId));
    }

    /* ==================================================================
     *  站内转单
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignToStaff(Long orderId, Long targetStaffId, boolean riskAcknowledged) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        requireNoPendingDirectedReturn(orderId);
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("仅待分配订单可分配");
        }
        // 收费站长也守同一道闸门：没收到钱的单不该被派出去（列表里看不到，但 id 是可编造的）
        if (!isPaidOrPayOnDelivery(order)) {
            throw new BusinessException("该订单尚未支付，暂时不能分配配送员：请客户完成支付后再派单");
        }
        if (targetStaffId == null) throw new BusinessException("请指定配送员");

        // [2026-09-18] **接收站的确认落点**：他站定向外派过来的单，履约站就是本站
        // （delivery_station_id = 本站，station_id = 归属站）。站长"分配配送员"是本站真正受理这一单的动作
        // —— 认领/接单类端点里只有它是站级、且对"已定履约站的单"仍然可用（claimTransfer 是员工级认领，
        // directedReturn 是拒收）。涉押金/桶权益的单在这里要第二次确认：外派方确认过一次（DISPATCH /
        // OUTSOURCE_DIRECT），接收站再确认一次，两边都留痕，才算产品要求的「双方都特别提醒后同意」。
        // 普通单（不涉押金/桶权益）不看这个字段 —— 不加无谓摩擦。
        boolean crossStation = order.getStationId() != null && !order.getStationId().equals(stationId);
        boolean risky = crossStation && involvesDepositOrBarrelRights(order);
        if (risky && !riskAcknowledged) {
            throw new BusinessException("接收他站外派的押金/桶权益单前必须先确认风险，本次操作未执行："
                    + DEPOSIT_BARREL_RISK_TEXT);
        }
        Staff target = requireDelivery(targetStaffId, stationId);

        // status 保持 1（待接单），配送员点"接单"后才变配送中
        int changed = orderMapper.setDeliveryStaffIf(orderId, targetStaffId, OrderStatus.PENDING);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        if (crossStation) dispatchAgreements.accepted(order,stationId);
        orderMapper.appendSpecialNote(orderId, "[分配] 站长分配给 " + target.getName());
        if (risky) appendRiskAckNote(orderId, "接收站", stationId);
        log("ASSIGN", orderId, serviceMap("targetStaffId", targetStaffId,
                "crossStation", crossStation, "depositBarrelRisk", risky, "riskAcknowledged", riskAcknowledged,
                "riskNote", risky ? DEPOSIT_BARREL_RISK_TEXT : null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferToStaff(Long orderId, Long targetStaffId, String reason) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) throw new BusinessException("无法识别当前水站");
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
        requireNoPendingDirectedReturn(orderId);
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("仅已分配/配送中的订单可转让");
        }
        if (AuthContext.isDelivery()
                && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            throw new BusinessException("只能转让自己名下的订单");
        }
        if (targetStaffId == null) throw new BusinessException("请指定接收配送员");
        if (order.getDeliveryStaffId() != null && order.getDeliveryStaffId().equals(targetStaffId)) {
            throw new BusinessException("不能转给自己");
        }
        Staff target = requireDelivery(targetStaffId, stationId);
        String r = reason != null ? reason : "配送员转让";

        // ⚠️ [2026-09-27 产品裁定：**转单必须双方同意**] 这里**不再**直接改 delivery_staff_id。
        // 原实现是"发起方单方面改派"——一提交订单就换人，接收方甚至不知道；
        // 产品口径是「如果双方都同意是可以转单的」，所以改成：
        //   ① 只落一条 **PENDING** 的 order_transfer（sub_kind=TRANSFER，from=我、to=他）；
        //   ② 订单**仍挂在发起人名下**（同意之前不该改归属），他在"我的单"里照常看得到；
        //   ③ 接收方在「转给我的单」里同意 → claimTransfer 里才改归属；
        //      拒绝 → rejectTransfer 把这条记录置 REJECTED，订单原地不动。
        // 这样"谁名下的单"与"待确认的转单"永远是两个独立事实，不会出现"记录说转出去了、
        // 但订单还挂在原配送员名下"的中间态假象（旧实现正是靠立即改派来掩盖这一点）。
        if (orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_STAFF) != null) {
            throw new BusinessException("该订单已有一条待接收方确认的转单，请等对方处理或先撤回");
        }
        insertTransfer(orderId, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_TRANSFER,
                order.getDeliveryStaffId(), targetStaffId, deliveryStation(order), r);
        orderMapper.appendSpecialNote(orderId, "[转让待确认] " + r + " -> 配送员 " + target.getName());
        log("TRANSFER_REQUEST", orderId, null);
    }

    /**
     * 撤回一笔还没被决策的转单（发起人反悔）。
     *
     * <p>[2026-09-18 修] 接线调研时发现旧实现缺两条判据，两条都会造成"看起来成功、实际没做成"：</p>
     * <ol>
     *   <li><b>没有校验"调用者是不是发起人"</b>：只有一句"仅能操作本站订单"，
     *       而 {@code OrderTransfer.fromStaffId} 一直存在、javadoc 也写着"或发起方主动 CANCELLED 撤回"。
     *       后果：A 把单转给 B 之后（{@code delivery_staff_id} 立即变成 B），**同站任意第三个配送员
     *       都能把这条转单撤掉** —— B 的"待接收"列表里静默少一条，而订单还挂在 B 名下、状态不变。</li>
     *   <li><b>丢弃了受影响行数</b>：{@code resolvePendingByKind} 的 javadoc 自己写着
     *       "返回受影响行数；0 表示无待决策转单"，旧实现不看返回值，于是对**根本没有待决策转单**的订单
     *       也返回成功，只往 {@code special_note} 塞一行「[取消转让]」（而它会显示在转单页的"备注"里）。
     *       属 §8.17 / §8.20 同族：用户以为做成了、账上没动。</li>
     * </ol>
     * <p>归属口径：<b>发起人本人</b>，或**本站站长**（站长代撤是站务常态，且既有用例
     * {@code CrossStationDispatchIntegrationTest} 正是用站长令牌驱动它）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTransfer(Long orderId) {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (stationId == null || !stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        // 先按 kind 定位待决策转单：拿不到就没有可撤的东西（**不要**先写 special_note 再判断）
        OrderTransfer pending = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_STAFF);
        if (pending == null) {
            throw new BusinessException("该订单当前没有待决策的转单，无需撤回");
        }
        boolean isInitiator = pending.getFromStaffId() != null && pending.getFromStaffId().equals(staffId);
        if (!isInitiator && !AuthContext.isManager()) {
            throw new BusinessException("只有转单的发起人（或本站站长）可以撤回这笔转单");
        }
        int affected = orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_STAFF,
                OrderTransfer.STATUS_CANCELLED, staffId);
        if (affected == 0) {
            throw new BusinessException("该转单已被处理，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId, "[取消转让]");
        log("CANCEL_TRANSFER", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimTransfer(Long orderId) {
        Long staffId = AuthContext.getUserId();
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        if (!Integer.valueOf(OrderStatus.PENDING).equals(order.getStatus())
                && !Integer.valueOf(OrderStatus.DELIVERING).equals(order.getStatus())) {
            throw new BusinessException("当前订单状态不可处理转让");
        }

        // ===== ① 同事转单的"同意"（[2026-09-27] 双方同意的第二半）=====
        // 待确认期间订单还挂在**发起人**名下，所以这一段必须在"订单是不是我的"那道校验**之前**判断
        // —— 否则接收方会看到「该订单已分配给其他配送员」，永远同意不了。
        OrderTransfer pending = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_STAFF);
        if (pending != null && OrderTransfer.SUB_TRANSFER.equals(pending.getSubKind())
                && staffId.equals(pending.getToStaffId())) {
            Long from = pending.getFromStaffId();
            // CAS：只有订单仍挂在发起人名下才改（防"两个人都点了同意"/中途被转给别人）
            int moved = orderMapper.reassignStaffIf(orderId, staffId, from);
            if (moved == 0) {
                throw new BusinessException("这条转单已经不作数了（订单已被改派或状态已变），请刷新后重试");
            }
            if (orderTransferMapper.resolvePendingRequest(pending.getId(), OrderTransfer.KIND_STAFF,
                    OrderTransfer.SUB_TRANSFER, OrderTransfer.STATUS_APPROVED, staffId) != 1) {
                throw new BusinessException("该转让已被处理，请刷新后重试");
            }
            orderMapper.appendSpecialNote(orderId, "[转让已接收]");
            log("TRANSFER_ACCEPT", orderId, null);
            return;
        }

        throw new BusinessException("该订单没有待你接手的转让，或该转让已经处理，请联系站长分配并刷新任务列表");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectTransfer(Long orderId) {
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        if (!Integer.valueOf(OrderStatus.PENDING).equals(order.getStatus())
                && !Integer.valueOf(OrderStatus.DELIVERING).equals(order.getStatus())) {
            throw new BusinessException("当前订单状态不可处理转让");
        }
        Long myStaffId = AuthContext.getUserId();

        // ===== 同事转单的"不同意"（[2026-09-27]）=====
        // 判据与同意对称：**待确认的转单指向我**才轮到我拒绝。拒绝后订单原地不动
        // （待确认期间它一直挂在发起人名下），发起人那边照常继续送。
        OrderTransfer pending = orderTransferMapper.findPendingByOrderAndKind(orderId, OrderTransfer.KIND_STAFF);
        if (pending != null && myStaffId != null && myStaffId.equals(pending.getToStaffId())) {
            int affected = orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_STAFF,
                    OrderTransfer.STATUS_REJECTED, myStaffId);
            if (affected == 0) {
                throw new BusinessException("这条转单已被处理，请刷新后重试");
            }
            orderMapper.appendSpecialNote(orderId, "[转让被拒]");
            log("TRANSFER_REJECT", orderId, null);
            return;
        }

        // [AQ-034] 旧实现零校验：任意配送员可拒绝任意水站任意订单，并往 special_note 里塞标记污染数据。
        Long stationId = AuthContext.getStationId();
        if (stationId == null) throw new BusinessException("无法识别当前水站");
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        if (AuthContext.isDelivery()
                && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            throw new BusinessException("只能拒绝自己名下的订单");
        }
        orderMapper.appendSpecialNote(orderId, "[拒绝认领]");
        log("REJECT_TRANSFER", orderId, null);
    }

    @Override
    public OrderTransfer pendingTransferOf(Long orderId) {
        // 只读：详情页回填转单状态用（为何经服务不进 Controller，见接口 javadoc）
        return orderTransferMapper.findPendingByOrder(orderId);
    }

    /* ==================================================================
     *  退回站长
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnToStation(Long orderId, String reason) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) throw new BusinessException("无法识别当前水站");
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
        requireNoPendingDirectedReturn(orderId);
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("仅已分配/配送中的订单可退回站长");
        }
        if (AuthContext.isDelivery()
                && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            throw new BusinessException("只能退回自己名下的订单");
        }
        String r = reason != null ? reason : "配送员退回站长";

        // AQ-016: 退回待审期间订单仍带原配送员（与 [退回站长] 标记配合，旧 UI 用 deliveryStaffId 判定"转单中"）。
        // 不可在此清空配送员，否则 rejectReturn 把状态置回 DELIVERING 时已无配送员可恢复 → 孤儿卡死。
        // 仅在 approveReturn（同意退回）时清空，转成真正待分配。
        if (cur != OrderStatus.PENDING) {
            int changed = orderMapper.updateStatusIf(orderId, cur, OrderStatus.PENDING);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
        }
        orderMapper.appendSpecialNote(orderId, "[退回站长] " + r);
        // [AQ-015] 结构化转单记录（权威状态源）
        insertTransfer(orderId, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_RETURN_STATION,
                order.getDeliveryStaffId(), null, deliveryStation(order), r);
        log("RETURN_TO_STATION", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveReturn(Long orderId) {
        AuthContext.requireManager();
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        resolveStaffReturnRequest(order, OrderTransfer.STATUS_APPROVED);
        // [AQ-016] 同意退回才清空配送员（转成真正待分配），且状态必须是待配送，
        // 否则会出现「配送中但无配送员」的孤儿卡死单。
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            int changed = orderMapper.updateStatusIf(orderId, cur, OrderStatus.PENDING);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
        }
        // [AQ-015] 写入决策子串「-已同意」，列表侧用 LIKE 排除；不做 replace 整列覆盖（并发下会丢更新）
        orderMapper.appendSpecialNote(orderId, "[退回站长-已同意] [退回通过]");
        orderMapper.clearDeliveryStaff(orderId);
        log("RETURN_APPROVE", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectReturn(Long orderId) {
        AuthContext.requireManager();
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        resolveStaffReturnRequest(order, OrderTransfer.STATUS_REJECTED);
        // [AQ-016] 拒绝退回须保证订单有配送员可继续履约，否则会变成「配送中但无配送员」的孤儿卡死单。
        if (order.getDeliveryStaffId() == null) {
            throw new BusinessException("订单当前无配送员，无法拒绝退回，请先分配配送员");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        int changed = orderMapper.updateStatusIf(orderId, cur, OrderStatus.DELIVERING);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId, "[退回站长-已拒绝] [退回拒绝]");
        log("RETURN_REJECT", orderId, null);
    }

    private void resolveStaffReturnRequest(Orders order, String outcome) {
        if (!Integer.valueOf(OrderStatus.PENDING).equals(order.getStatus())
                && !Integer.valueOf(OrderStatus.DELIVERING).equals(order.getStatus())) {
            throw new BusinessException("当前订单状态不可审批退回");
        }
        OrderTransfer request = orderTransferMapper.findPendingByOrderAndKind(order.getId(), OrderTransfer.KIND_STAFF);
        if (request == null || !OrderTransfer.SUB_RETURN_STATION.equals(request.getSubKind())) {
            throw new BusinessException("该订单没有待审批的退回站长申请，请使用对应申请的处理入口");
        }
        if (orderTransferMapper.resolvePendingRequest(request.getId(), OrderTransfer.KIND_STAFF,
                OrderTransfer.SUB_RETURN_STATION, outcome, AuthContext.getUserId()) != 1) {
            throw new BusinessException("该退回申请已被处理，请刷新后重试");
        }
    }

    /* ==================================================================
     *  站间指定退回
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturn(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅目标水站可退回此单");
        }
        if (stationId.equals(order.getStationId())) {
            throw new BusinessException("本站订单请使用退回站长入口");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("当前状态不可退回");
        }
        if (orderTransferMapper.countPendingByOrder(orderId) != 0) {
            throw new BusinessException("该订单已有待处理申请，请先处理原申请");
        }
        if (cur == OrderStatus.DELIVERING && order.getDeliveryStaffId() == null) {
            throw new BusinessException("配送中订单缺少负责人，请先核实原配送安排");
        }
        // 申请独立记 PENDING，保留实际是否接单及原指派，审批不得凭有配送员就推定已接单。
        orderMapper.appendSpecialNote(orderId,
                "[指定退回待确认] 由水站 " + stationId + " 申请退回原归属站 " + order.getStationId());
        Long requestId = insertTransfer(orderId, OrderTransfer.KIND_DIRECTED, OrderTransfer.SUB_DIRECTED_RETURN,
                order.getDeliveryStaffId(), null, stationId, "申请退回原归属站 " + order.getStationId());
        log("DIRECTED_RETURN", orderId,
                serviceMap("fromStationId", stationId, "toStationId", order.getStationId(),
                        "transferId", requestId, "sourceStatus", cur));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturnApprove(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅原归属站可操作");
        }
        OrderTransfer request = requirePendingDirectedReturn(order);
        int changed = orderMapper.directedReturnApproveIf(orderId, stationId, OrderStatus.PENDING,
                order.getStatus());
        if (changed == 0) {
            throw new BusinessException("该订单状态已变更，请刷新后重试");
        }
        dispatchAgreements.recallIfUnstarted(orderId);
        // 同意指定退回 = 回归属站 → 待收款流水跟着回归属站
        movePendingCollectionTo(orderId, stationId);
        // [2026-09-25 库存预留模型] 预留一并退回归属站（同"召回"：货跟着履约站走）
        inventoryReservationService.transferForOrder(orderId, stationId);
        resolveDirectedReturn(request, OrderTransfer.STATUS_APPROVED);
        log("DIRECTED_RETURN_APPROVE", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturnReject(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrderForUpdate(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅原归属站可操作");
        }
        OrderTransfer request = requirePendingDirectedReturn(order);
        int restoredStatus = directedReturnSourceStatus(order, request);
        int changed = orderMapper.directedReturnRejectIf(orderId, restoredStatus, order.getStatus());
        if (changed == 0) {
            throw new BusinessException("该订单状态已变更，请刷新后重试");
        }
        resolveDirectedReturn(request, OrderTransfer.STATUS_REJECTED);
        log("DIRECTED_RETURN_REJECT", orderId, serviceMap("transferId", request.getId(), "restoredStatus", restoredStatus));
    }

    private OrderTransfer requirePendingDirectedReturn(Orders order) {
        int status = order.getStatus() == null ? 0 : order.getStatus();
        OrderTransfer request = orderTransferMapper.findPendingByOrderAndKind(order.getId(), OrderTransfer.KIND_DIRECTED);
        if ((status != OrderStatus.PENDING && status != OrderStatus.DELIVERING)
                || request == null || !OrderTransfer.SUB_DIRECTED_RETURN.equals(request.getSubKind())
                || java.util.Objects.equals(order.getStationId(), deliveryStation(order))
                || !java.util.Objects.equals(request.getFromStationId(), deliveryStation(order))
                || !java.util.Objects.equals(request.getFromStaffId(), order.getDeliveryStaffId())) {
            throw new BusinessException("指定退回申请或原配送安排已变化，请刷新后核实");
        }
        return request;
    }

    private void resolveDirectedReturn(OrderTransfer request, String outcome) {
        if (orderTransferMapper.resolvePendingRequest(request.getId(), OrderTransfer.KIND_DIRECTED,
                OrderTransfer.SUB_DIRECTED_RETURN, outcome, AuthContext.getUserId()) != 1) {
            throw new BusinessException("该指定退回申请已被处理，请刷新后重试");
        }
    }

    /** 新申请核对审计快照；旧申请按真实指派/接单凭据恢复，证据不全只保留原站同意后重排出口。 */
    private int directedReturnSourceStatus(Orders order, OrderTransfer request) {
        Map<String, Object> event = orderMapper.latestDirectedReturnEvent(order.getId());
        Long source = auditNumber(event, "sourceStatus");
        if (source != null) {
            if (!java.util.Objects.equals(auditNumber(event, "transferId"), request.getId())
                    || source.intValue() != order.getStatus()
                    || (source != OrderStatus.PENDING && source != OrderStatus.DELIVERING)
                    || (source == OrderStatus.DELIVERING && order.getDeliveryStaffId() == null)) {
                throw new BusinessException("原配送状态或申请已变化，请核实后处理");
            }
            return source.intValue();
        }
        if (order.getStatus() == OrderStatus.DELIVERING && order.getDeliveryStaffId() != null) return OrderStatus.DELIVERING;
        if (order.getDeliveryStaffId() == null) return OrderStatus.PENDING;
        if (event != null && event.get("id") instanceof Number) {
            Map<String, Object> previous = orderMapper.orderStateEventBefore(order.getId(), ((Number) event.get("id")).longValue());
            if (previous != null) {
                String action = String.valueOf(previous.get("action"));
                if ("ACCEPT".equals(action) && java.util.Objects.equals(auditNumber(previous, "deliveryStaffId"), request.getFromStaffId())) return OrderStatus.DELIVERING;
                if ("CLAIM_POOL".equals(action) && java.util.Objects.equals(auditNumber(previous, "staffId"), request.getFromStaffId())
                        && java.util.Objects.equals(auditNumber(previous, "stationId"), request.getFromStationId())) return OrderStatus.DELIVERING;
                if ("RETURN_REJECT".equals(action)) return OrderStatus.DELIVERING;
                if ("ASSIGN".equals(action) && java.util.Objects.equals(auditNumber(previous, "targetStaffId"), request.getFromStaffId())) return OrderStatus.PENDING;
                if (Set.of("DISPATCH", "OUTSOURCE_DIRECT", "OUTSOURCE", "RETURN_TO_STATION",
                        "RETURN_APPROVE", "CANCEL_DISPATCH", "DIRECTED_RETURN_APPROVE").contains(action)) return OrderStatus.PENDING;
                if ("DIRECTED_RETURN_REJECT".equals(action)) {
                    Long restored = auditNumber(previous, "restoredStatus");
                    if (restored == null || restored == OrderStatus.DELIVERING) return OrderStatus.DELIVERING;
                    if (restored == OrderStatus.PENDING) return OrderStatus.PENDING;
                }
                if ("STATION_REJECT".equals(action) && String.valueOf(previous.get("detail")).contains("tryDispatch=true")) return OrderStatus.PENDING;
            }
        }
        throw new BusinessException("原配送状态未能核实，请先核实或同意退回后重新安排");
    }

    private Long auditNumber(Map<String, Object> event, String field) {
        if (event == null) return null;
        var match = java.util.regex.Pattern.compile("(?:\\{|,\\s*)" + field + "=(\\d+)(?:,|\\})")
                .matcher(String.valueOf(event.get("detail")));
        return match.find() ? Long.valueOf(match.group(1)) : null;
    }

    /* ==================================================================
     *  取消申请（已接单订单的取消须站长审批）
     *
     *  背景：此前配送员可经 rejectOrder 直接取消已接单订单（含退款），零审批；
     *  客户对已接单订单则完全取消不了。现统一为「申请 → 站长决策」：
     *    配送员/客户 → requestCancelBy*（写 order_transfer，PENDING，不动订单状态）
     *                → 站长 approveCancelRequest（refundOrder 完整退款链）/ rejectCancelRequest
     *  与「退回站长」的区别：退回 = 订单继续（变待分配）；取消 = 订单终止。
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestCancelByStaff(Long orderId, String reason) {
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        requireNoPendingDirectedReturn(orderId);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        // [2026-09-22 修] 本入口**只收「配送中(2)」**，三个状态各有各的正门：
        //   待配送(1) → 还没接单，配送员直接走 `rejectOrder`（不经审批，少一步）；
        //   配送中(2) → 本入口（提交申请 → 站长审批）；
        //   已送达(3) → 货已交付、**不可取消**（`isCancellable` 排除），异常走「配送异常」。
        // ⚠️ 原来这里写的是 `cur != DELIVERING && cur != DELIVERED`（**放行了已送达**），
        //    而下游 `approveCancelRequest` 用的是 `isCancellable`（拒绝已送达）—— **两个入口口径不一致**。
        //    后果：配送员能给一张已送达的单提交取消申请，它进了站长的 P0 审批列表，
        //    站长点「同意」才被拒（"点了才发现拒不了"），唯一的出路是点「拒绝」——
        //    等于凭空给站长派了一件只能驳回的活。
        // **判据只留一处：`OrderStatus.isCancellable`。**
        if (cur != OrderStatus.DELIVERING) {
            throw new BusinessException(OrderStatus.isCancellable(cur)
                    ? "待配送的订单不需要审批：请直接「拒单」"
                    : OrderStatus.notCancellableReason(cur, "申请取消"));
        }
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending != null && OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("该订单已有待审批的取消申请，请勿重复提交");
        }
        String r = (reason != null && !reason.isBlank()) ? reason : "配送员申请取消";
        insertTransfer(orderId, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_CANCEL_REQUEST,
                AuthContext.getUserId(), null, deliveryStation(order), r);
        orderMapper.appendSpecialNote(orderId, "[取消申请] " + r);
        log("CANCEL_REQUEST_STAFF", orderId, serviceMap("reason", r));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestCancelByCustomer(Long orderId, Long customerId, String reason) {
        Orders order = requireOrderForUpdate(orderId);
        if (order.getCustomerId() == null || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权取消他人订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException(OrderStatus.notCancellableReason(cur));
        }
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending != null && OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("已提交取消申请，请等待水站处理");
        }
        String r = (reason != null && !reason.isBlank()) ? reason : "客户申请取消";
        insertTransfer(orderId, OrderTransfer.KIND_CUSTOMER, OrderTransfer.SUB_CANCEL_REQUEST,
                null, null, deliveryStation(order), r);
        orderMapper.appendSpecialNote(orderId, "[取消申请] " + r);
        log("CANCEL_REQUEST_CUSTOMER", orderId, serviceMap("reason", r));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveCancelRequest(Long orderId) {
        // 与送达统一锁序：订单→准确申请→退款/桶账；先锁申请会与送达形成反向等待。
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending == null || !OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("该订单没有待审批的取消申请");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException(OrderStatus.notCancellableReason(cur));
        }
        // 先落审批结论，再走统一退款编排（refundOrder 末尾统一把订单置为已取消）
        if(orderTransferMapper.resolvePendingRequest(pending.getId(),pending.getKind(),OrderTransfer.SUB_CANCEL_REQUEST,
                OrderTransfer.STATUS_APPROVED,AuthContext.getUserId())!=1)throw new BusinessException("该取消申请已经处理，请刷新后核实");
        String reason = (pending.getReason() != null && !pending.getReason().isBlank())
                ? pending.getReason() : "取消申请";
        cancelWithRefund(orderId, reason, "[取消申请-已同意] ");
        if(cancelResults.insert(pending.getId(),"水站已同意取消，订单已取消；退款到账以实际办理结果为准。",false,AuthContext.getUserId())!=1)
            throw new BusinessException("取消申请处理结果未保存");
        notifyCustomerRejected(order.getCustomerId(), orderId, reason);
        log("CANCEL_REQUEST_APPROVE", orderId, serviceMap("transferId", pending.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCancelRequest(Long orderId) {
        Orders order = requireOrderForUpdate(orderId);
        checkStationOwnership(order);
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending == null || !OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("该订单没有待审批的取消申请");
        }
        // 驳回：订单状态保持不变（继续配送中/已送达），仅落审批结论
        if(orderTransferMapper.resolvePendingRequest(pending.getId(),pending.getKind(),OrderTransfer.SUB_CANCEL_REQUEST,
                OrderTransfer.STATUS_REJECTED,AuthContext.getUserId())!=1)throw new BusinessException("该取消申请已经处理，请刷新后核实");
        if(cancelResults.insert(pending.getId(),"水站未同意本次取消申请，订单保持当前状态；如有异议可联系水站。",false,AuthContext.getUserId())!=1)
            throw new BusinessException("取消申请处理结果未保存");
        orderMapper.appendSpecialNote(orderId, "[取消申请-已驳回]");
        log("CANCEL_REQUEST_REJECT", orderId, serviceMap("transferId", pending.getId()));
    }

    /**
     * 从 params 里取一个**可空**整数。
     *
     * <p>JSON 数字经 Jackson 到 Java 可能是 Integer / Long / Double，直接强转 Integer 会 ClassCastException
     * （那会被兜成 500 —— 本仓对"可预期的输入问题"一律要求 code=1，见 AGENTS §8.21 的同族判据）。</p>
     */
    private static Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new BusinessException("数字格式不正确");
        }
    }

    /** 便捷构造：Map.of 不允许 null 值，这里统一用 LinkedHashMap */
    private Map<String, Object> serviceMap(Object... kv) {        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
