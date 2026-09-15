package com.example.aquaflow.service.impl;

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

/**
 * {@link OrderWorkflowService} 实现。
 *
 * <p><b>Phase C 变更要点</b>：本类的方法体由 {@code DeliveryController} 与
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

    @Autowired
    private OrderMapper orderMapper;

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

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    /** 桶权益总账：全系统唯一的桶账写入口 */
    @Autowired
    private BarrelLedgerService barrelLedgerService;

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

    /** 履约站（delivery_station_id 优先，回退 station_id） */
    private Long deliveryStation(Orders o) {
        return StationUtil.deliveryStation(o);
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
    private void insertTransfer(Long orderId, String kind, String subKind,
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
        Long stationId = AuthContext.getStationId();

        Orders order = requireOrder(orderId);
        if (stationId != null && !stationId.equals(deliveryStation(order))) {
            throw new BusinessException("只能接本站履约的订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单当前状态不可接单");
        }
        if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(staffId)) {
            throw new BusinessException("该订单已分配给其他配送员");
        }

        // 原子接单：仅当 status=1 才更新，返回受影响行数（乐观锁）
        int affected = orderMapper.updateStatusIfPENDING(orderId, OrderStatus.DELIVERING, staffId);
        if (affected == 0) {
            throw new BusinessException("接单失败，订单状态已变更，请刷新后重试");
        }
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
        Orders order = requireOrder(orderId);
        checkStationOwnership(order);
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

        // 支付状态 CAS：待收款/未付 → 已付（重复确认时幂等跳过）
        int payCur = order.getPaymentStatus() != null ? order.getPaymentStatus() : PaymentStatus.UNPAID;
        if (payCur != PaymentStatus.PAID) {
            int payAffected = orderMapper.updatePaymentStatusIf(orderId, payCur, PaymentStatus.PAID);
            if (payAffected == 0) {
                throw new BusinessException("支付状态已变更，请刷新后重试");
            }
        }
        // 已送达则顺带闭环为已完成
        if (cur == OrderStatus.DELIVERED) {
            orderMapper.updateStatusIf(orderId, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
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
        Orders order = requireOrder(orderId);
        checkStationOwnership(order);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        if (order.getStatus() != OrderStatus.DELIVERING) {
            throw new BusinessException("该订单当前状态不可完成配送");
        }

        // 首次桶装水订单：押金桶无需回桶，直接跳过回桶核对
        boolean isFirstBarrelOrder = Boolean.TRUE.equals(order.getFirstBarrelOrder());

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
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> list = (List<Map<String, Object>>) ir;
                    itemReturns = list;
                    for (Map<String, Object> item : list) {
                        Object actual = item.get("actual");
                        if (actual instanceof Number) {
                            int act = ((Number) actual).intValue();
                            returnBucketQty += act;
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
                }
            }
            // 兼容旧版 returnBucketQty 参数（无商品维度，按订单明细数量比例分摊）
            if (itemReturns == null && params != null && params.containsKey("returnBucketQty")) {
                Object rb = params.get("returnBucketQty");
                if (rb instanceof Number) {
                    returnBucketQty = ((Number) rb).intValue();
                    returnedByProduct = splitByItemRatio(orderItems, returnBucketQty);
                }
            }
        }
        if (returnBucketQty < 0) {
            throw new BusinessException("回收空桶数不能为负数");
        }

        // ===== 桶账（全系统唯一写入口）=====
        // newOver = oldOver + (delivered − returned) − rightPurchase，按商品结算；
        // 唯一校验是【物理上限】returned <= 占用_before(权益+over)，over 允许为负（多还桶/水站暂存）。
        BarrelLedgerService.DeliveryOutcome ledgerOutcome =
                barrelLedgerService.applyDelivery(orderId, order.getCustomerId(), order.getStationId(),
                        returnedByProduct, staffId);
        int owed = ledgerOutcome.totalOverDelta();

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
                            String key = r.get("key") != null ? r.get("key").toString() : "other";
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

        // 处理桶差异异常录入
        Long exceptionId = null;
        if (owed != 0) {
            OrderBarrelExceptionService.ReturnInput input = new OrderBarrelExceptionService.ReturnInput();
            input.setActualReturn(returnBucketQty);
            input.setStaffAction(owed > 0 ? "PARTIAL" : "FULL");
            input.setStaffNote(discrepancyNote);
            try {
                OrderBarrelExceptionService.OrderBarrelExceptionDTO ex =
                        orderBarrelExceptionService.recordReturn(orderId, input);
                exceptionId = ex.getId();
            } catch (Exception e) {
                log.error("[OrderWorkflow] 录入回桶异常失败: orderId={}", orderId, e);
            }
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
                // [AQ-043] 跨站外派单（归属站 != 履约站）：收款与欠桶账都归原归属站。
                // 禁止目标站（履约站）替归属站确认收款并闭环订单，否则钱记到履约站、欠桶却记到归属站。
                Long ownerStation = order.getStationId();
                Long fulfillStation = deliveryStation(order);
                if (ownerStation != null && fulfillStation != null && !ownerStation.equals(fulfillStation)) {
                    Long myStation = AuthContext.getStationId();
                    if (myStation == null || !myStation.equals(ownerStation)) {
                        throw new BusinessException("跨站外派订单仅原归属站可确认收款，请由归属站操作");
                    }
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

        // 支付状态 CAS
        int payCur = order.getPaymentStatus() != null ? order.getPaymentStatus() : PaymentStatus.UNPAID;
        int payTarget = markPaid ? PaymentStatus.PAID : PaymentStatus.UNPAID;
        if (payCur != payTarget) {
            orderMapper.updatePaymentStatusIf(orderId, payCur, payTarget);
        }
        // [AQ-009] 只要订单最终为已付款，就在此刻入账预收桶押金（幂等，重复调用安全）
        if (markPaid) {
            paymentService.applyDepositOnPaid(orderId);
        }

        // 纯数据字段回写（回桶数 / 欠桶数 / 差异说明 / 异常单号），不含状态
        orderMapper.updateDeliveryOutcome(orderId, returnBucketQty, owed, discrepancyNote, exceptionId);

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
        Orders order = requireOrder(orderId);
        // 配送员与站长都必须校验订单归属，杜绝越权取消任意订单并触发退款
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("无权操作他站订单");
        }
        // [2026-09-13] 状态门槛：此前这里只校验归属、不校验状态，
        // 于是「配送员点完成」与「配送员/站长点拒单」在两个客户端上没有任何互斥，
        // 且已完成的订单也能被拒单退款。业务上只允许 待配送/配送中/已送达 拒单。
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException("该订单当前状态不可拒单（status=" + cur + "）");
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
        Orders order = requireOrder(orderId);
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("拒单原因必填");
        }
        // [2026-09-13] 与 rejectOrder 同款状态门槛：已完成/已取消的订单不允许再走退款
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException("该订单当前状态不可解决/拒单（status=" + cur + "）");
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
        Orders order = requireOrder(orderId);
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        String r = reason != null ? reason : "站长拒单";

        if (tryDispatch) {
            int cur = order.getStatus() != null ? order.getStatus() : 0;
            // 原子：清空履约站与配送员、状态回到待配送（原实现是先 update 再 clearDispatchStation 两次写）
            int changed = orderMapper.outsourceToPoolIf(orderId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            orderMapper.appendSpecialNote(orderId,
                    "[外派] 站长拒单后外派，原因=" + r + "，原归属站=" + stationId);
        } else {
            // [2026-09-13] 取消分支原先没有任何状态门槛（外派分支靠 outsourceToPoolIf 的 CAS 兜着）。
            // 与 rejectOrder/resolveOrder 统一：只允许 待配送/配送中/已送达。
            int curCancel = order.getStatus() != null ? order.getStatus() : 0;
            if (!OrderStatus.isCancellable(curCancel)) {
                throw new BusinessException("该订单当前状态不可取消（status=" + curCancel + "）");
            }
            cancelWithRefund(orderId, r, "[拒单] " + r);
            notifyCustomerRejected(order.getCustomerId(), orderId, r);
        }
        log("STATION_REJECT", orderId, serviceMap("reason", r, "tryDispatch", tryDispatch));
    }

    /* ==================================================================
     *  派单 / 外派 / 召回 / 抢单
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispatchExternal(Long orderId, Long targetStationId, String reason) {
        Long myStationId = AuthContext.getStationId();
        if (myStationId == null) throw new BusinessException("无法识别当前水站");

        Orders order = requireOrder(orderId);
        if (!myStationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
        if (targetStationId == null) throw new BusinessException("targetStationId 不能为空");
        if (targetStationId.equals(myStationId)) throw new BusinessException("不能外派给自己水站");

        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("当前状态不可外派调度");
        }
        String r = reason != null ? reason : "外派配送";

        // [AQ-020] 仅修改 delivery_station_id、清空配送员；归属站保持不变。CAS 于状态。
        int dispatched = orderMapper.dispatchIfStatus(orderId, targetStationId, OrderStatus.PENDING);
        if (dispatched == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId,
                "[外派] 从水站 " + myStationId + " 外派至 " + targetStationId + "，原因：" + r);
        notifyCustomerTempDispatch(order.getCustomerId(), orderId, targetStationId);
        log("DISPATCH", orderId,
                serviceMap("orderId", orderId, "fromStationId", myStationId, "toStationId", targetStationId, "reason", r));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void outsource(Long orderId, Long targetStationId, String reason) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("当前状态不可外派");
        }

        if (targetStationId != null) {
            if (targetStationId.equals(stationId)) throw new BusinessException("不能外派给自己水站");
            String r = reason != null ? reason : "站长指定水站外派";
            int changed = orderMapper.outsourceToStationIf(orderId, targetStationId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            orderMapper.appendSpecialNote(orderId,
                    "[外派] 站长指定外派至 " + targetStationId + "，原因：" + r + "，原归属站=" + stationId);
            notifyCustomerTempDispatch(order.getCustomerId(), orderId, targetStationId);
            log("OUTSOURCE_DIRECT", orderId,
                    serviceMap("fromStationId", stationId, "toStationId", targetStationId, "reason", r));
        } else {
            int changed = orderMapper.outsourceToPoolIf(orderId, OrderStatus.PENDING, cur);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
            orderMapper.appendSpecialNote(orderId, "[外派] 站长放入抢单池，原归属站=" + stationId);
            log("OUTSOURCE", orderId, serviceMap("stationId", stationId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelDispatch(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅能取消本站外派的订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("该订单状态不可取消外派");
        }
        // 召回为本站待分配（无论当前在抢单池、已指定或已被其他站接单）
        int changed = orderMapper.recallToStationIf(orderId, stationId, OrderStatus.PENDING, cur);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId, "[取消外派] 站长取消外派，恢复本站");
        log("CANCEL_DISPATCH", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimPool(Long orderId, Long targetStaffId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        // 必须是抢单池中的订单（delivery_station_id 为空）
        if (order.getDeliveryStationId() != null) {
            throw new BusinessException("该订单已被其他水站抢单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("该订单当前状态不可抢单");
        }
        if (targetStaffId == null) throw new BusinessException("请指定配送员");
        Staff target = requireDelivery(targetStaffId, stationId);

        // [AQ-020] 原子 CAS：仅当订单仍在池中（delivery_station_id 为空）且状态=待配送时才算抢到
        int grabbed = orderMapper.claimPoolIfFree(orderId, stationId, targetStaffId,
                OrderStatus.DELIVERING, OrderStatus.PENDING);
        if (grabbed == 0) {
            throw new BusinessException("该订单已被其他水站抢单");
        }
        orderMapper.appendSpecialNote(orderId, " [抢单] " + stationId + "站抢单成功，配送员=" + target.getName());
        notifyCustomerTempDispatch(order.getCustomerId(), orderId, stationId);
        log("CLAIM_POOL", orderId, serviceMap("stationId", stationId, "staffId", targetStaffId));
    }

    /* ==================================================================
     *  站内转单
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignToStaff(Long orderId, Long targetStaffId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            throw new BusinessException("仅待分配订单可分配");
        }
        if (targetStaffId == null) throw new BusinessException("请指定配送员");
        Staff target = requireDelivery(targetStaffId, stationId);

        // status 保持 1（待接单），配送员点"接单"后才变配送中
        int changed = orderMapper.setDeliveryStaffIf(orderId, targetStaffId, OrderStatus.PENDING);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId, "[分配] 站长分配给 " + target.getName());
        log("ASSIGN", orderId, serviceMap("targetStaffId", targetStaffId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferToStaff(Long orderId, Long targetStaffId, String reason) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) throw new BusinessException("无法识别当前水站");
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
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

        // [AQ-015] 结构化转单记录（转让），须在改派前取原配送员
        insertTransfer(orderId, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_TRANSFER,
                order.getDeliveryStaffId(), targetStaffId, deliveryStation(order), r);
        int changed = orderMapper.setDeliveryStaffIf(orderId, targetStaffId, cur);
        if (changed == 0) {
            throw new BusinessException("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(orderId, "[转让] " + r + " -> 配送员 " + target.getName());
        log("TRANSFER", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTransfer(Long orderId) {
        Long stationId = AuthContext.getStationId();
        Orders order = requireOrder(orderId);
        if (stationId == null || !stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
        orderMapper.appendSpecialNote(orderId, "[取消转让]");
        // [AQ-015] 结构化：把该单待决策的配送员转单置为已取消
        orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_STAFF,
                OrderTransfer.STATUS_CANCELLED, AuthContext.getUserId());
        log("CANCEL_TRANSFER", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimTransfer(Long orderId) {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();
        Orders order = requireOrder(orderId);
        if (stationId != null && !stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能认领本站订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("当前订单状态不可认领");
        }
        if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(staffId)) {
            throw new BusinessException("该订单已分配给其他配送员");
        }
        // [AQ-020] 原子 CAS：仅当订单无配送员（或归自己）时更新
        int claimed = orderMapper.claimIfUnassigned(orderId, staffId);
        if (claimed == 0) {
            throw new BusinessException("该订单已被其他配送员认领");
        }
        orderMapper.appendSpecialNote(orderId, "[认领]");
        log("CLAIM", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectTransfer(Long orderId) {
        Orders order = requireOrder(orderId);
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

    /* ==================================================================
     *  退回站长
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnToStation(Long orderId, String reason) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) throw new BusinessException("无法识别当前水站");
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站履约的订单");
        }
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
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
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
        orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_STAFF,
                OrderTransfer.STATUS_APPROVED, AuthContext.getUserId());
        log("RETURN_APPROVE", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectReturn(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅能操作本站订单");
        }
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
        orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_STAFF,
                OrderTransfer.STATUS_REJECTED, AuthContext.getUserId());
        log("RETURN_REJECT", orderId, null);
    }

    /* ==================================================================
     *  站间指定退回
     * ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturn(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(deliveryStation(order))) {
            throw new BusinessException("仅目标水站可退回此单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            throw new BusinessException("当前状态不可退回");
        }
        // 进入「转单中」：不动 delivery_station_id / delivery_staff_id，拒绝时才能还原由原配送员继续配送
        if (cur != OrderStatus.PENDING) {
            int changed = orderMapper.updateStatusIf(orderId, cur, OrderStatus.PENDING);
            if (changed == 0) {
                throw new BusinessException("订单状态已变更，请刷新后重试");
            }
        }
        orderMapper.appendSpecialNote(orderId,
                "[指定退回待确认] 由水站 " + stationId + " 申请退回原归属站 " + order.getStationId());
        // [AQ-015] 结构化转单记录（站间指定退回待确认）
        insertTransfer(orderId, OrderTransfer.KIND_DIRECTED, OrderTransfer.SUB_DIRECTED_RETURN,
                order.getDeliveryStaffId(), null, stationId, "申请退回原归属站 " + order.getStationId());
        log("DIRECTED_RETURN", orderId,
                serviceMap("fromStationId", stationId, "toStationId", order.getStationId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturnApprove(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅原归属站可操作");
        }
        if (order.getSpecialNote() == null || !order.getSpecialNote().contains("[指定退回待确认]")) {
            throw new BusinessException("该订单无需确认退回");
        }
        // CAS 守卫在备注标记上：并发两次「同意」只有一个能改到
        int changed = orderMapper.directedReturnApproveIf(orderId, stationId, OrderStatus.PENDING);
        if (changed == 0) {
            throw new BusinessException("该订单状态已变更，请刷新后重试");
        }
        orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_DIRECTED,
                OrderTransfer.STATUS_APPROVED, AuthContext.getUserId());
        log("DIRECTED_RETURN_APPROVE", orderId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void directedReturnReject(Long orderId) {
        Long stationId = AuthContext.requireStationId();
        Orders order = requireOrder(orderId);
        if (!stationId.equals(order.getStationId())) {
            throw new BusinessException("仅原归属站可操作");
        }
        if (order.getSpecialNote() == null || !order.getSpecialNote().contains("[指定退回待确认]")) {
            throw new BusinessException("该订单无需确认退回");
        }
        // 拒绝转单 -> 回到配送中；delivery_station_id / delivery_staff_id 保持原样
        int changed = orderMapper.directedReturnRejectIf(orderId, OrderStatus.DELIVERING);
        if (changed == 0) {
            throw new BusinessException("该订单状态已变更，请刷新后重试");
        }
        orderTransferMapper.resolvePendingByKind(orderId, OrderTransfer.KIND_DIRECTED,
                OrderTransfer.STATUS_REJECTED, AuthContext.getUserId());
        log("DIRECTED_RETURN_REJECT", orderId, null);
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
        Orders order = requireOrder(orderId);
        checkStationOwnership(order);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        // 待配送(1) 尚未接单，配送员可直接走 rejectOrder；只有已接单才需要审批
        if (cur != OrderStatus.DELIVERING && cur != OrderStatus.DELIVERED) {
            throw new BusinessException("仅已接单（配送中/已送达）的订单需提交取消申请，当前状态=" + cur);
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
        Orders order = requireOrder(orderId);
        if (order.getCustomerId() == null || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权取消他人订单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException("当前订单状态不可取消，如需帮助请联系水站");
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
        Orders order = requireOrder(orderId);
        checkStationOwnership(order);
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending == null || !OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("该订单没有待审批的取消申请");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (!OrderStatus.isCancellable(cur)) {
            throw new BusinessException("该订单当前状态不可取消（status=" + cur + "）");
        }
        // 先落审批结论，再走统一退款编排（refundOrder 末尾统一把订单置为已取消）
        orderTransferMapper.resolvePendingByKind(orderId, pending.getKind(),
                OrderTransfer.STATUS_APPROVED, AuthContext.getUserId());
        String reason = (pending.getReason() != null && !pending.getReason().isBlank())
                ? pending.getReason() : "取消申请";
        cancelWithRefund(orderId, reason, "[取消申请-已同意] ");
        notifyCustomerRejected(order.getCustomerId(), orderId, reason);
        log("CANCEL_REQUEST_APPROVE", orderId, serviceMap("transferId", pending.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCancelRequest(Long orderId) {
        Orders order = requireOrder(orderId);
        checkStationOwnership(order);
        OrderTransfer pending = orderTransferMapper.findPendingByOrder(orderId);
        if (pending == null || !OrderTransfer.SUB_CANCEL_REQUEST.equals(pending.getSubKind())) {
            throw new BusinessException("该订单没有待审批的取消申请");
        }
        // 驳回：订单状态保持不变（继续配送中/已送达），仅落审批结论
        orderTransferMapper.resolvePendingByKind(orderId, pending.getKind(),
                OrderTransfer.STATUS_REJECTED, AuthContext.getUserId());
        orderMapper.appendSpecialNote(orderId, "[取消申请-已驳回]");
        log("CANCEL_REQUEST_REJECT", orderId, serviceMap("transferId", pending.getId()));
    }

    /** 便捷构造：Map.of 不允许 null 值，这里统一用 LinkedHashMap */
    private Map<String, Object> serviceMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
