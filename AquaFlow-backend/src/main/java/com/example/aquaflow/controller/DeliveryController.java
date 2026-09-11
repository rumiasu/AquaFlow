package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.CustomerBarrelOwedMapper;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelOwed;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.entity.CustomerNotification;
import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.mapper.OrderTransferMapper;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配送员订单接口
 */
@RestController
@RequestMapping("/api/delivery")
@Slf4j
public class DeliveryController {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PaymentService paymentService;

    /** [AQ-015] 转单状态结构化存储 */
    @Autowired
    private OrderTransferMapper orderTransferMapper;

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelOwedMapper customerBarrelOwedMapper;

    /** 桶权益总账：全系统唯一的桶账写入口（over 结算 + 押金条/权益批次） */
    @Autowired
    private com.example.aquaflow.service.BarrelLedgerService barrelLedgerService;

    @Autowired
    private com.example.aquaflow.mapper.CustomerNotificationMapper customerNotificationMapper;

    @Autowired
    private com.example.aquaflow.mapper.StationMapper stationMapper;

    private void checkStationOwnership(Orders order) {
        // 强制当前水站非空（未绑站直接拒绝，fail-closed），并严格比对履约站
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

    /** 给客户发送"订单因水站原因被取消/拒单"提醒 */
    private void notifyCustomerRejected(Long customerId, Long orderId, String reason) {
        if (customerId == null) return;
        CustomerNotification n = new CustomerNotification();
        n.setCustomerId(customerId);
        n.setType("REJECTED");
        n.setTitle("订单已取消");
        n.setContent("订单 #" + orderId + " 因水站原因被取消，拒绝接单。" + (reason != null && !reason.isEmpty() ? "原因：" + reason : ""));
        n.setRelatedOrderId(orderId);
        n.setIsRead(0);
        n.setCreateTime(LocalDateTime.now());
        customerNotificationMapper.insert(n);
    }

    /** 给客户发送"临时由其他水站配送"提醒 */
    private void notifyCustomerTempDispatch(Long customerId, Long orderId, Long targetStationId) {
        if (customerId == null) return;
        String stationName = "";
        if (targetStationId != null) {
            com.example.aquaflow.entity.Station s = stationMapper.getById(targetStationId);
            if (s != null && s.getName() != null) {
                stationName = s.getName();
            }
        }
        CustomerNotification n = new CustomerNotification();
        n.setCustomerId(customerId);
        n.setType("TEMP_DISPATCH");
        n.setTitle("临时外派配送");
        n.setContent("因水站原因，订单 #" + orderId + " 临时由" + (stationName.isEmpty() ? "其他水站" : stationName + "水站") + "代替送达。");
        n.setRelatedOrderId(orderId);
        n.setIsRead(0);
        n.setCreateTime(LocalDateTime.now());
        customerNotificationMapper.insert(n);
    }

    private Long deliveryStation(Orders o) {
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    private String appendNote(String existing, String part) {
        if (part == null || part.trim().isEmpty()) {
            return existing;
        }
        return (existing != null && !existing.trim().isEmpty()) ? existing + " " + part.trim() : part.trim();
    }

    /** [AQ-015] 写入一条待决策转单记录（结构化权威状态源） */
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

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/pending")
    public Result<?> getPendingOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listPendingByStationId(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/assigned-to-me")
    public Result<?> getAssignedToMeOrders() {
        Long staffId = AuthContext.getUserId();
        // 配送员待接单：分配给我但 status 仍为 1 的订单
        return Result.success(orderMapper.listAssignedToStaff(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivering")
    public Result<?> getDeliveringOrders() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/completed-today")
    public Result<?> getCompletedToday() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listByDeliveryStaffIdAndDate(staffId, OrderStatus.COMPLETED, java.time.LocalDate.now()));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-pending")
    public Result<?> getStationPendingOrders() {
        Long stationId = AuthContext.getStationId();
        // 只返回未分配配送员的待分配订单
        return Result.success(orderMapper.listStationPendingUnassigned(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-delivering")
    public Result<?> getStationDeliveringOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.DELIVERING));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-completed")
    public Result<?> getStationCompletedOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.COMPLETED));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-transfer")
    public Result<?> getStationTransferOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listTransferredOrders(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-return")
    public Result<?> getStationReturnOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listStationReturnOrders(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-exception")
    public Result<?> getStationExceptionOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listStationExceptionOrders(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/{id}")
    public Result<?> getOrderDetail(@PathVariable Long id) {
        Orders order = orderMapper.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if (AuthContext.isDelivery()) {
            checkStationOwnership(order);
            if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(AuthContext.getUserId())) {
                return Result.error("无权查看其他配送员的订单");
            }
        } else if (AuthContext.isManager()) {
            checkStationOwnership(order);
        }
        order.setItems(orderItemMapper.listByOrderId(id));
        // 「待我确认的转单」由后端按登录人判定（前端此前读的 isTransferTarget 后端并不存在）
        order.setTransferTarget(isTransferTarget(order));
        return Result.success(order);
    }

    /**
     * 判定订单是否为「转给当前登录人、待其确认」的转单。
     * <p>站间指定退回 -> 归属站站长决策；配送员转单 -> 本站站长可决策，
     * 或未分配/已分配给本人的配送员可认领（与 claimTransfer 的校验口径保持一致）。</p>
     */
    private boolean isTransferTarget(Orders order) {
        if (!order.getTransferPending()) return false;
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();
        if ("DIRECTED".equals(order.getTransferKind())) {
            return stationId != null && stationId.equals(order.getStationId());
        }
        if (stationId != null && stationId.equals(deliveryStation(order))) return true;
        return order.getDeliveryStaffId() == null
                || (staffId != null && staffId.equals(order.getDeliveryStaffId()));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/accept")
    @Transactional
    public Result<Void> acceptOrder(@PathVariable Long id) {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();

        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (stationId != null && !stationId.equals(deliveryStation(order))) return Result.error("只能接本站履约的订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            return Result.error("该订单当前状态不可接单");
        }
        if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(staffId)) {
            return Result.error("该订单已分配给其他配送员");
        }

        // #16: 原子更新 — 用updateStatus的返回值判断是否成功（乐观锁）
        int affected = orderMapper.updateStatusIfPENDING(id, OrderStatus.DELIVERING, staffId);
        if (affected == 0) {
            return Result.error("接单失败，订单状态已变更，请刷新后重试");
        }

        // 更新本地对象用于日志
        order.setDeliveryStaffId(staffId);
        order.setStatus(OrderStatus.DELIVERING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [接单] 配送员ID=" + staffId));
        order.setUpdateTime(LocalDateTime.now());

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", id);
        d.put("deliveryStaffId", staffId);
        log("ACCEPT", id, d);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/confirm-offline-pay")
    @Transactional
    public Result<Void> confirmOfflinePay(@PathVariable Long id) {
        Long staffId = AuthContext.getUserId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        checkStationOwnership(order);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        // 仅现金(货到付款)订单需配送员现场确认收款；微信(线上回调)与水票(已扣减)不在此处理
        if (order.getPaymentMethod() == null || !Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod())) {
            return Result.error("仅现金(货到付款)订单可确认收款");
        }
        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.DELIVERED) {
            return Result.error("当前状态不可确认线下收款");
        }

        order.setPaymentStatus(PaymentStatus.PAID);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            order.setStatus(OrderStatus.COMPLETED);
        }
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [线下收款确认] 配送员ID=" + staffId));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        // [AQ-002] 置「已付款」必须补写 PAID 支付流水，否则订单已付款却无凭证，日结对不上
        paymentService.recordCashCollection(id);
        // [AQ-009] 收款后入账预收桶押金（幂等）
        paymentService.applyDepositOnPaid(id);

        log("CONFIRM_OFFLINE_PAY", id, null);
        return Result.success();
    }

    /**
     * 旧客户端只回传一个总回桶数、没有商品维度时，按订单明细数量比例分摊（余数补给最后一项）。
     * 这是兼容兜底，正常路径（itemReturns 带 orderItemId）不会走到这里。
     */
    /**
     * 把本次配送的<b>物理桶收发</b>写进 barrel_record（type=8），每个商品一行。
     *
     * <p>为什么必须写：权益账（lot / over）自洽只能证明"账做得平"，
     * 证明不了"顾客手上真有这么多桶"。只有留下送出/收回的流水，
     * 对账 V2 的 E5 才能用流水反推占用，跟「权益 + over」交叉验证——
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
            r.setDepositRefund(java.math.BigDecimal.ZERO); // 配送不涉及退款
            r.setNote("送出 " + delivered + " / 收回 " + returned + " / 本单新购权益 " + purchased);
            r.setOperatorId(staffId);
            r.setCreateTime(LocalDateTime.now());
            barrelRecordMapper.insert(r);
        }
    }

    private Map<Long, Integer> splitByItemRatio(List<OrderItem> items, int total) {
        Map<Long, Integer> result = new HashMap<>();
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

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/complete")
    @Transactional
    public Result<Void> completeOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Long staffId = AuthContext.getUserId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        checkStationOwnership(order);
        if (AuthContext.isDelivery()) {
            checkDeliverySelf(order);
        }
        if (order.getStatus() != OrderStatus.DELIVERING) return Result.error("该订单当前状态不可完成配送");

        // 首次桶装水订单：押金桶无需回桶，直接跳过回桶核对
        boolean isFirstBarrelOrder = Boolean.TRUE.equals(order.getFirstBarrelOrder());

        // 解析 itemReturns 数组（按商品核对回桶）
        // 【不信任客户端】商品维度一律由后端用 orderItemId 反查 order_item.product_id 得到，
        // 客户端只提供 orderItemId 与数量。productName 仅用于生成差异说明文案。
        int returnBucketQty = 0;
        List<Map<String, Object>> itemReturns = null;
        Map<Long, Integer> returnedByProduct = new HashMap<>();
        List<OrderItem> orderItems = orderItemMapper.listByOrderId(id);
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
                                return Result.error("回桶明细缺少有效的商品信息，请更新小程序后重试");
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
        if (returnBucketQty < 0) return Result.error("回收空桶数不能为负数");
        order.setReturnBucketQty(returnBucketQty);

        // ===== 桶账（全系统唯一写入口）=====
        // 旧实现有两处错误，已整体废弃：
        //   ① owed = delivered − returned，漏减「本单新购权益数 rightPurchase」——
        //      只有新购为 0 的换桶场景才恰好正确，所以主流程一直没暴露；
        //   ② maxReturn = delivered − credit 硬拦截，把「家里空桶全还、这次少买」直接拒单。
        // 新实现：newOver = oldOver + (delivered − returned) − rightPurchase，按商品结算；
        // 唯一校验是【物理上限】returned <= 占用_before(权益+over)，over 允许为负（多还桶/水站暂存）。
        // 首次桶装水订单无需特判：right=0、over=0、买3送3收0 → newOver = 0，公式自然成立。
        BarrelLedgerService.DeliveryOutcome ledgerOutcome;
        try {
            ledgerOutcome = barrelLedgerService.applyDelivery(
                    id, order.getCustomerId(), order.getStationId(), returnedByProduct, staffId);
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
        int owed = ledgerOutcome.totalOverDelta();
        order.setBarrelDiscrepancy(owed);

        // 物理桶流水留痕（type=8 配送收发明细）。
        // 没有它，对账只能校验「权益账自洽」，无法回答最要命的那个问题：
        //   「这个顾客手上到底应该有几个桶？」
        // 有了它，对账 V2 的 E5 才能用流水重算占用，跟 权益+over 交叉验证。
        recordDeliveryBarrels(order, ledgerOutcome, staffId);

        // 从 itemReturns 构建差异说明
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
            if (noteSb.length() > 0) {
                order.setBarrelDiscrepancyNote(noteSb.toString());
            }
        } else if (params != null && params.containsKey("barrelDiscrepancyNote")) {
            order.setBarrelDiscrepancyNote((String) params.get("barrelDiscrepancyNote"));
        }

        if (params != null && params.containsKey("note")) {
            String note = (String) params.get("note");
            order.setSpecialNote(appendNote(order.getSpecialNote(), " [配送备注] " + note));
        }

        // ===== 支付前置校验（[AQ-002][AQ-007]，必须早于桶异常录入等副作用） =====
        // 原则：PAID 只能由支付链路（createPayment / confirmPayment / 现金现场收款）写入，
        // 配送员点"完成"绝不能是付款动作。否则未付款的微信单 / 未扣票的水票单会被白送。
        Integer pm = order.getPaymentMethod();
        boolean paid = paymentService.hasPaidRecord(id);
        boolean isCashOnDelivery = pm == null || Integer.valueOf(PayMethod.CASH).equals(pm);
        boolean collected = false;
        if (params != null && params.containsKey("collected")) {
            Object c = params.get("collected");
            collected = Boolean.TRUE.equals(c);
        }

        // 微信(1) / 水票(3)：必须有 PAID 流水才能完成，否则直接拒绝。
        if (!isCashOnDelivery && !paid) {
            return Result.error("该订单尚未完成支付，请先完成支付再配送（微信需支付回调，水票需先扣减）");
        }

        // 处理桶差异异常录入
        if (owed != 0) {
            OrderBarrelExceptionService.ReturnInput input = new OrderBarrelExceptionService.ReturnInput();
            input.setActualReturn(returnBucketQty);
            input.setStaffAction(owed > 0 ? "PARTIAL" : "FULL");
            input.setStaffNote(order.getBarrelDiscrepancyNote());

            try {
                OrderBarrelExceptionService.OrderBarrelExceptionDTO ex = orderBarrelExceptionService.recordReturn(id, input);
                order.setBarrelExceptionId(ex.getId());
            } catch (Exception e) {
                log.error("[Delivery] 录入回桶异常失败: orderId={}", id, e);
            }
        }

        if (isCashOnDelivery) {
            if (paid) {
                // 已收款（例：站长已确认），直接完成；collected 不再影响付款状态
                order.setStatus(OrderStatus.COMPLETED);
                order.setPaymentStatus(PaymentStatus.PAID);
            } else if (collected) {
                // [AQ-043] 跨站外派单（归属站 != 履约站）：收款与欠桶账都归原归属站。
                // 禁止目标站（履约站）替归属站确认收款并闭环订单，否则钱记到履约站、欠桶却记到归属站，账实错位。
                Long ownerStation = order.getStationId();
                Long fulfillStation = deliveryStation(order);
                if (ownerStation != null && fulfillStation != null && !ownerStation.equals(fulfillStation)) {
                    Long myStation = AuthContext.getStationId();
                    if (myStation == null || !myStation.equals(ownerStation)) {
                        return Result.error("跨站外派订单仅原归属站可确认收款，请由归属站操作");
                    }
                }
                order.setStatus(OrderStatus.COMPLETED);
                order.setPaymentStatus(PaymentStatus.PAID);
                paymentService.recordCashCollection(id);   // 补写流水，保证账证一致
            } else {
                order.setStatus(OrderStatus.DELIVERED);
                order.setPaymentStatus(PaymentStatus.UNPAID);
            }
        } else {
            // 微信 / 水票：到此必 paid == true（已在上面拦截），正常完成
            order.setStatus(OrderStatus.COMPLETED);
            order.setPaymentStatus(PaymentStatus.PAID);
        }

        // [AQ-009] 只要订单最终为已付款，就在此刻入账预收桶押金（幂等，重复调用安全）。
        // 押金不再于下单时入账，这是其唯一入口，覆盖现金现场收款 / 站长确认 / 线上支付完成各条路径。
        if (Integer.valueOf(PaymentStatus.PAID).equals(order.getPaymentStatus())) {
            paymentService.applyDepositOnPaid(id);
        }

        // 配送中权益转正（建押金条 lot + 增加权益）已由 BarrelLedgerService.applyDelivery 完成，
        // 配送中记录标记 DELIVERED 而非物理删除（DEF-6）——「已购待送权益」需要可追溯。

        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        Map<String, Object> d = new HashMap<>();
        d.put("returnBucketQty", returnBucketQty);
        d.put("barrelDiscrepancy", owed);
        d.put("isCashOnDelivery", isCashOnDelivery);
        d.put("collected", collected);
        d.put("finalStatus", order.getStatus());
        log("COMPLETE", id, d);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivered-unpaid")
    public Result<?> getDeliveredUnpaid() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.DELIVERED));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/reject/{id}")
    @Transactional
    public Result<Void> rejectOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        // 配送员与站长都必须校验订单归属，杜绝越权取消任意订单并触发退款
        Long stationId = AuthContext.requireStationId();
        if (!stationId.equals(deliveryStation(order))) return Result.error("无权操作他站订单");
        String reason = params != null && params.get("reason") != null ? params.get("reason").toString() : "水站拒单";
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [拒单] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        // 触发退款（退支付记录 + 退押金账户 + 清配送中桶），由 refundOrder 统一置为 CANCELLED
        paymentService.refundOrder(id, reason);

        // 给客户发送拒单提醒
        notifyCustomerRejected(order.getCustomerId(), id, reason);

        log("REJECT", id, null);
        return Result.success();
    }

    /**
     * 外派订单：临时指派给其他水站配送，客户归属不变
     * 仅修改 delivery_station_id，owner_station_id 保持不变
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/dispatch")
    @Transactional
    public Result<Void> dispatchOrder(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.getStationId();
        if (myStationId == null) return Result.error("无法识别当前水站");

        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅能操作本站履约的订单");

        Object targetStationIdObj = params.get("targetStationId");
        if (targetStationIdObj == null) return Result.error("targetStationId 不能为空");
        Long targetStationId = ((Number) targetStationIdObj).longValue();
        if (targetStationId.equals(myStationId)) return Result.error("不能外派给自己水站");

        // #19: 校验状态 — DELIVERING不允许重置为PENDING
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            return Result.error("当前状态不可外派调度");
        }

        String reason = params != null && params.get("reason") != null ? params.get("reason").toString() : "外派配送";

        // 仅修改 delivery_station_id，owner_station_id 保持不变
        // [AQ-020] 原为 check-then-act（先查状态再 update）→ 并发下可重复外派/覆盖。改为 CAS。
        int dispatched = orderMapper.dispatchIfStatus(id, targetStationId, OrderStatus.PENDING);
        if (dispatched == 0) {
            return Result.error("订单状态已变更，请刷新后重试");
        }
        orderMapper.appendSpecialNote(id, " [外派] 从水站 " + myStationId + " 外派至 " + targetStationId + "，原因：" + reason);

        // 给客户发送临时外派配送提醒
        notifyCustomerTempDispatch(order.getCustomerId(), id, targetStationId);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", id);
        d.put("fromStationId", myStationId);
        d.put("toStationId", targetStationId);
        d.put("reason", reason);
        log("DISPATCH", id, d);
        return Result.success();
    }

    /**
     * 解决订单：拒单并取消订单，触发退款
     * 客户需重新下单，款项原路退回
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/resolve")
    @Transactional
    public Result<Void> resolveOrder(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.getStationId();
        if (myStationId == null) return Result.error("无法识别当前水站");

        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅能操作本站履约的订单");

        Object reasonObj = params.get("reason");
        if (reasonObj == null) return Result.error("拒单原因必填");
        String reason = reasonObj.toString();

        // 注意：不要提前置 CANCELLED，否则 refundOrder 的状态门槛(orderStatus < DELIVERED)会失效、
        // 导致押金不退/配送中桶悬挂。状态由 refundOrder 末尾统一置位。
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [解决/拒单] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        // 触发退款流水
        paymentService.refundOrder(id, reason);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", id);
        d.put("reason", reason);
        d.put("refundProcessed", true);
        log("RESOLVE", id, d);

        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/barrel-records")
    public Result<?> getBarrelRecords() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listBarrelRecords(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}")
    public Result<Void> transferOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) return Result.error("无法识别当前水站");
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站履约的订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("仅已分配/配送中的订单可转让");
        if (AuthContext.isDelivery() && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            return Result.error("只能转让自己名下的订单");
        }
        Object staffIdObj = params != null ? params.get("deliveryStaffId") : null;
        if (staffIdObj == null) return Result.error("请指定接收配送员");
        Long targetId = ((Number) staffIdObj).longValue();
        if (order.getDeliveryStaffId() != null && order.getDeliveryStaffId().equals(targetId)) {
            return Result.error("不能转给自己");
        }
        Staff target = requireDelivery(targetId, stationId);
        String reason = params != null && params.get("reason") != null ? params.get("reason").toString() : "配送员转让";
        // [AQ-015] 结构化转单记录（转让），须在改派前取原配送员
        insertTransfer(id, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_TRANSFER,
                order.getDeliveryStaffId(), targetId, deliveryStation(order), reason);
        order.setDeliveryStaffId(targetId);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [转让] " + reason + " -> 配送员 " + target.getName()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        log("TRANSFER", id, null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/return/{id}")
    public Result<Void> returnToStation(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Long stationId = AuthContext.getStationId();
        if (stationId == null) return Result.error("无法识别当前水站");
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站履约的订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("仅已分配/配送中的订单可退回站长");
        if (AuthContext.isDelivery() && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            return Result.error("只能退回自己名下的订单");
        }
        String reason = params != null && params.get("reason") != null ? params.get("reason").toString() : "配送员退回站长";
        // AQ-016: 退回待审期间订单仍带原配送员（与 [退回站长] 标记配合，旧 UI 用 deliveryStaffId 判定"转单中"）。
        // 不可在此清空配送员，否则 rejectReturn 把状态置回 DELIVERING 时已无配送员可恢复 → 孤儿卡死。
        // 仅在 approveReturn（同意退回）时清空，转成真正待分配。
        // [AQ-015] 备注改为 DB 侧原子追加，避免并发整列覆盖丢更新；状态更新走 CAS。
        int changed = orderMapper.updateStatusIf(id, cur, OrderStatus.PENDING);
        if (changed == 0) return Result.error("订单状态已变更，请刷新后重试");
        orderMapper.appendSpecialNote(id, "[退回站长] " + reason);
        // [AQ-015] 结构化转单记录（权威状态源）
        insertTransfer(id, OrderTransfer.KIND_STAFF, OrderTransfer.SUB_RETURN_STATION,
                order.getDeliveryStaffId(), null, deliveryStation(order), reason);

        log("RETURN_TO_STATION", id, null);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/assign/{id}")
    public Result<Void> assignOrder(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) return Result.error("仅待分配订单可分配");
        Object staffIdObj = params.get("deliveryStaffId");
        if (staffIdObj == null) return Result.error("请指定配送员");
        Long targetId = ((Number) staffIdObj).longValue();
        Staff target = requireDelivery(targetId, stationId);
        order.setDeliveryStaffId(targetId);
        // status 保持 1（待接单），配送员点"接单"后才变 3
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [分配] 站长分配给 " + target.getName()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        log("ASSIGN", id, Collections.singletonMap("targetStaffId", targetId));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer/{id}/outsource")
    @Transactional
    public Result<Void> outsourceOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可外派");

        Object targetObj = params != null ? params.get("targetStationId") : null;
        if (targetObj != null) {
            // 指定水站外派：直接派给目标站，订单进入目标站待分配
            Long targetStationId = ((Number) targetObj).longValue();
            if (targetStationId.equals(stationId)) return Result.error("不能外派给自己水站");
            String reason = params.get("reason") != null ? params.get("reason").toString() : "站长指定水站外派";
            order.setDeliveryStationId(targetStationId);
            order.setDeliveryStaffId(null);
            order.setStatus(OrderStatus.PENDING);
            order.setSpecialNote(appendNote(order.getSpecialNote(),
                    " [外派] 站长指定外派至 " + targetStationId + "，原因：" + reason + "，原归属站=" + stationId));
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.update(order);
            orderMapper.clearDeliveryStaff(id); // 清空原配送员
            // 给客户发送临时外派配送提醒
            notifyCustomerTempDispatch(order.getCustomerId(), id, targetStationId);
            log("OUTSOURCE_DIRECT", id, Map.of("fromStationId", stationId, "toStationId", targetStationId, "reason", reason));
        } else {
            // 放入抢单池：清空 delivery_station_id，station_id（归属站）不变
            order.setDeliveryStationId(null);
            order.setDeliveryStaffId(null);
            order.setStatus(OrderStatus.PENDING);
            order.setSpecialNote(appendNote(order.getSpecialNote(), " [外派] 站长放入抢单池，原归属站=" + stationId));
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.update(order);
            log("OUTSOURCE", id, Map.of("stationId", stationId));
        }
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/cancel")
    public Result<Void> cancelTransfer(@PathVariable Long id) {
        Long stationId = AuthContext.getStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [取消转让]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        // [AQ-015] 结构化：把该单待决策的配送员转单置为已取消
        orderTransferMapper.resolvePendingByKind(id, OrderTransfer.KIND_STAFF, OrderTransfer.STATUS_CANCELLED, AuthContext.getUserId());
        log("CANCEL_TRANSFER", id, null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/claim")
    public Result<Void> claimTransfer(@PathVariable Long id) {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        // #18: 校验站点归属
        if (stationId != null && !stationId.equals(deliveryStation(order))) {
            return Result.error("仅能认领本站订单");
        }
        // #18: 校验订单状态
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            return Result.error("当前订单状态不可认领");
        }
        // #18: 校验订单未被其他配送员认领
        if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(staffId)) {
            return Result.error("该订单已分配给其他配送员");
        }
        // [AQ-020] 原为 check-then-act，并发下两个配送员可同时认领。改为原子 CAS：仅当订单无配送员（或归自己）时更新。
        int claimed = orderMapper.claimIfUnassigned(id, staffId);
        if (claimed == 0) {
            return Result.error("该订单已被其他配送员认领");
        }
        orderMapper.appendSpecialNote(id, "[认领]");
        log("CLAIM", id, null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/reject")
    public Result<Void> rejectTransfer(@PathVariable Long id) {
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        // [AQ-034] 旧实现零校验：任意配送员可拒绝任意水站任意订单，并往 special_note 里塞标记污染数据。
        Long stationId = AuthContext.getStationId();
        if (stationId == null) return Result.error("无法识别当前水站");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        if (AuthContext.isDelivery()
                && (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            return Result.error("只能拒绝自己名下的订单");
        }
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [拒绝认领]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        log("REJECT_TRANSFER", id, null);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return/{id}/approve")
    public Result<Void> approveReturn(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        // [AQ-015] 备注原子追加；[AQ-016] 同意退回才清空配送员（转成真正待分配）。
        // 关键：写入决策子串「-已同意」，列表侧用 LIKE 排除；不再做 replace 整列覆盖（并发下会丢更新）。
        orderMapper.appendSpecialNote(id, "[退回站长-已同意] [退回通过]");
        orderMapper.updateStatus(id, OrderStatus.PENDING);
        orderMapper.clearDeliveryStaff(id); // 选择性更新下 setDeliveryStaffId(null) 不写库，必须显式清空
        // [AQ-015] 结构化：把该单待决策的配送员转单置为已同意
        orderTransferMapper.resolvePendingByKind(id, OrderTransfer.KIND_STAFF, OrderTransfer.STATUS_APPROVED, AuthContext.getUserId());
        log("RETURN_APPROVE", id, null);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return/{id}/reject")
    public Result<Void> rejectReturn(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        // [AQ-015] 备注原子追加（写入「-已拒绝」决策子串，列表侧 LIKE 排除）。
        // [AQ-016] 拒绝退回须保证订单有配送员可继续履约，否则会变成「配送中但无配送员」的孤儿卡死单。
        if (order.getDeliveryStaffId() == null) {
            return Result.error("订单当前无配送员，无法拒绝退回，请先分配配送员");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        int changed = orderMapper.updateStatusIf(id, cur, OrderStatus.DELIVERING);
        if (changed == 0) return Result.error("订单状态已变更，请刷新后重试");
        orderMapper.appendSpecialNote(id, "[退回站长-已拒绝] [退回拒绝]");
        // [AQ-015] 结构化：把该单待决策的配送员转单置为已拒绝
        orderTransferMapper.resolvePendingByKind(id, OrderTransfer.KIND_STAFF, OrderTransfer.STATUS_REJECTED, AuthContext.getUserId());
        log("RETURN_REJECT", id, null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/stats/today")
    public Result<Map<String, Object>> getTodayStats() {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();

        Map<String, Object> stats = new HashMap<>();
        var completed = orderMapper.listByDeliveryStaffIdAndDate(staffId, OrderStatus.COMPLETED, java.time.LocalDate.now());
        stats.put("completedCount", completed.size());
        var delivering = orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING);
        stats.put("deliveringCount", delivering.size());
        var pending = orderMapper.list(stationId, null, OrderStatus.PENDING, null, null, null, null);
        stats.put("pendingCount", pending.size());
        int totalReturn = completed.stream()
                .mapToInt(o -> o.getReturnBucketQty() != null ? o.getReturnBucketQty() : 0)
                .sum();
        stats.put("returnBarrels", totalReturn);
        // 待收款订单数：以前端读 stats.unpaidOrders，但后端从未下发该字段，看板恒显示 0。
        // 改为后端按 payment_status / payment_method 真实统计（水票视同已付，不计入）。
        stats.put("unpaidOrders", stationId == null ? 0 : orderMapper.countUncollected(stationId));

        return Result.success(stats);
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/history")
    public Result<?> getDeliveryHistory() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listHistoryByDeliveryStaffId(staffId, OrderStatus.COMPLETED));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers")
    public Result<?> getTransferRecords() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listTransferredOrders(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers/incoming")
    public Result<?> getIncomingTransfers() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listIncomingTransfers(staffId));
    }

    // ==================== 抢单池 & 外派追踪 ====================

    /**
     * 抢单池列表：获取同城市+距离范围内外派订单
     * 仅返回 delivery_station_id IS NULL 的订单
     * 包含商品匹配信息（辅助提示，不硬拦截）
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/pool")
    public Result<?> getPoolOrders() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> poolOrders = orderMapper.listPoolOrders(stationId);

        // 获取本站所有商品（用于匹配）
        List<com.example.aquaflow.entity.Product> stationProducts =
                productMapper.listByStationId(stationId);

        // 为每个订单计算商品匹配结果
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Orders order : poolOrders) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("id", order.getId());
            orderData.put("customerName", order.getCustomerName());
            orderData.put("customerPhone", order.getCustomerPhone());
            orderData.put("receiverName", order.getReceiverName());
            orderData.put("receiverPhone", order.getReceiverPhone());
            orderData.put("addressDetail", order.getAddressDetail());
            orderData.put("addressSnapshot", order.getAddressSnapshot());
            orderData.put("quantity", order.getQuantity());
            orderData.put("createTime", order.getCreateTime());

            // 从 order_item 获取商品名称（一单可能有多商品，取第一个）
            String orderProductName = "";
            List<com.example.aquaflow.entity.OrderItem> items = order.getItems();
            if (items != null && !items.isEmpty()) {
                orderProductName = items.get(0).getProductNameSnapshot();
                orderData.put("productName", orderProductName);
            }

            // 商品匹配
            Map<String, String> matchResult = checkProductMatch(orderProductName, stationProducts);
            orderData.put("productMatch", matchResult);

            result.add(orderData);
        }

        return Result.success(result);
    }

    /**
     * 商品匹配算法：提取品牌关键词进行模糊匹配
     * 返回：{ level: "full"/"partial"/"none", matchName: "匹配的商品名", hint: "提示文案" }
     */
    private Map<String, String> checkProductMatch(String orderProductName,
                                                   List<com.example.aquaflow.entity.Product> stationProducts) {
        Map<String, String> result = new HashMap<>();
        if (orderProductName == null || orderProductName.isEmpty()) {
            result.put("level", "none");
            result.put("hint", "未找到匹配商品");
            return result;
        }

        String orderBrand = extractBrand(orderProductName);

        // 精确匹配
        for (com.example.aquaflow.entity.Product p : stationProducts) {
            String stationBrand = extractBrand(p.getName());
            if (orderBrand.equals(stationBrand) || orderBrand.contains(stationBrand) || stationBrand.contains(orderBrand)) {
                result.put("level", "full");
                result.put("matchName", p.getName());
                result.put("hint", "高度匹配");
                return result;
            }
        }

        // 检查是否有同类商品（如都是"桶装水"）
        for (com.example.aquaflow.entity.Product p : stationProducts) {
            String stationName = p.getName() != null ? p.getName() : "";
            // 检查是否都包含"桶装水"、"矿泉水"、"纯净水"等关键词
            if (isSameCategory(orderProductName, stationName)) {
                result.put("level", "partial");
                result.put("matchName", p.getName());
                result.put("hint", "疑似匹配，请确认库存");
                return result;
            }
        }

        result.put("level", "none");
        result.put("hint", "未找到匹配商品");
        return result;
    }

    /**
     * 品牌关键词提取：去掉规格、包装等信息
     * "农夫山泉纯净水 550ml×24" → "农夫山泉"
     */
    private String extractBrand(String productName) {
        if (productName == null) return "";
        return productName
                .replaceAll("\\d+[mMlL升L]{1,2}", "")      // 移除容量 550ml, 1.5L
                .replaceAll("\\d+×\\d+", "")                // 移除包装 24×1
                .replaceAll("\\d+\\*\\d+", "")              // 移除包装 24*1
                .replaceAll("(桶装|瓶装|整箱|大桶|小桶)", "") // 移除包装词
                .replaceAll("(纯净水|矿泉水|天然水|饮用水|山泉水|水)", "") // 移除水类型
                .replaceAll("\\s+", "")                      // 移除空格
                .trim();
    }

    /**
     * 判断两个商品是否属于同类（如都是桶装水）
     */
    private boolean isSameCategory(String name1, String name2) {
        String[] categories = {"桶装水", "矿泉水", "纯净水", "天然水", "饮用水", "山泉水"};
        for (String cat : categories) {
            if (name1.contains(cat) && name2.contains(cat)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从抢单池抢单：指定配送员接单
     * delivery_station_id = 本站，订单变为本站配送
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/claim-pool")
    @Transactional
    public Result<Void> claimPoolOrder(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        // 必须是抢单池中的订单（delivery_station_id 为空）
        if (order.getDeliveryStationId() != null) {
            return Result.error("该订单已被其他水站抢单");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            return Result.error("该订单当前状态不可抢单");
        }

        Object staffIdObj = params.get("deliveryStaffId");
        if (staffIdObj == null) return Result.error("请指定配送员");
        Long targetStaffId = ((Number) staffIdObj).longValue();
        Staff target = requireDelivery(targetStaffId, stationId);

        // [AQ-020] 原为 check-then-act，两个水站可同时抢到同一单（后写覆盖前写）。
        // 改为原子 CAS：仅当订单仍在池中（delivery_station_id 为空）且状态=待配送时才算抢到。
        int grabbed = orderMapper.claimPoolIfFree(id, stationId, targetStaffId,
                OrderStatus.DELIVERING, OrderStatus.PENDING);
        if (grabbed == 0) {
            return Result.error("该订单已被其他水站抢单");
        }
        orderMapper.appendSpecialNote(id, " [抢单] " + stationId + "站抢单成功，配送员=" + target.getName());

        // 抢单成功：给客户发送临时外派配送提醒（告知由本站代替送达）
        notifyCustomerTempDispatch(order.getCustomerId(), id, stationId);

        log("CLAIM_POOL", id, Map.of("stationId", stationId, "staffId", targetStaffId));
        return Result.success();
    }

    /**
     * 外派追踪列表：本站外派出去的订单状态
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/dispatch-tracking")
    public Result<?> getDispatchTracking() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> dispatched = orderMapper.listDispatchedOrders(stationId);
        return Result.success(dispatched);
    }

    /**
     * 取消外派：将订单从抢单池移除，恢复为本站待分配
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/cancel-dispatch")
    @Transactional
    public Result<Void> cancelDispatch(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        // 验证是本站外派的订单（归属站）
        Long ownerStation = order.getStationId();
        if (!stationId.equals(ownerStation)) return Result.error("仅能取消本站外派的订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            return Result.error("该订单状态不可取消外派");
        }
        // 召回为本站待分配（无论当前在抢单池、已指定或已被其他站接单）
        order.setDeliveryStationId(stationId);
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [取消外派] 站长取消外派，恢复本站"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        orderMapper.clearDeliveryStaff(id); // 清空配送员，恢复本站待分配（update 为选择性更新，null 不会写库）

        log("CANCEL_DISPATCH", id, null);
        return Result.success();
    }

    /**
     * 指定水站外派后，目标水站将订单调解退回原归属站。
     * 仅打标记 [指定退回待确认]（即「转单中」），保留原履约站与原配送员，等待原站长决定：
     * 同意 -> 变回原站普通待分配（可重新分配/外派）；
     * 拒绝 -> 回到配送中，由原配送员继续完成配送。
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return")
    @Transactional
    public Result<Void> directedReturn(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅目标水站可退回此单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可退回");
        // 进入「转单中」：不动 delivery_station_id / delivery_staff_id，拒绝时才能还原由原配送员继续配送
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(),
                " [指定退回待确认] 由水站 " + stationId + " 申请退回原归属站 " + order.getStationId()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        // [AQ-015] 结构化转单记录（站间指定退回待确认）
        insertTransfer(id, OrderTransfer.KIND_DIRECTED, OrderTransfer.SUB_DIRECTED_RETURN,
                order.getDeliveryStaffId(), null, stationId, "申请退回原归属站 " + order.getStationId());
        log("DIRECTED_RETURN", id, Map.of("fromStationId", stationId, "toStationId", order.getStationId()));
        return Result.success();
    }

    /**
     * 原归属站站长同意退回：订单变为普通待分配，可重新分配/外派
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return/approve")
    @Transactional
    public Result<Void> directedReturnApprove(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(order.getStationId())) return Result.error("仅原归属站可操作");
        if (order.getSpecialNote() == null || !order.getSpecialNote().contains("[指定退回待确认]"))
            return Result.error("该订单无需确认退回");
        String note = order.getSpecialNote().replace("[指定退回待确认]", "").replace("[外派]", "").trim();
        order.setSpecialNote((note.isEmpty() ? "" : note + " ") + "[指定退回-同意]");
        order.setDeliveryStationId(stationId);
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        orderMapper.clearDeliveryStaff(id); // 清空配送员，变回普通待分配（update 为选择性更新，null 不会写库）
        // [AQ-015] 结构化：指定退回置为已同意
        orderTransferMapper.resolvePendingByKind(id, OrderTransfer.KIND_DIRECTED, OrderTransfer.STATUS_APPROVED, AuthContext.getUserId());
        log("DIRECTED_RETURN_APPROVE", id, null);
        return Result.success();
    }

    /**
     * 原归属站站长拒绝退回：取消转单，订单回到「配送中」，由原配送员继续完成配送
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return/reject")
    @Transactional
    public Result<Void> directedReturnReject(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(order.getStationId())) return Result.error("仅原归属站可操作");
        if (order.getSpecialNote() == null || !order.getSpecialNote().contains("[指定退回待确认]"))
            return Result.error("该订单无需确认退回");
        String note = order.getSpecialNote().replace("[指定退回待确认]", "").trim();
        order.setSpecialNote((note.isEmpty() ? "" : note + " ") + "[指定退回-拒绝]");
        // 拒绝转单 -> 回到配送中；delivery_station_id / delivery_staff_id 保持原样，由原配送员继续配送
        order.setStatus(OrderStatus.DELIVERING);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        // [AQ-015] 结构化：指定退回置为已拒绝
        orderTransferMapper.resolvePendingByKind(id, OrderTransfer.KIND_DIRECTED, OrderTransfer.STATUS_REJECTED, AuthContext.getUserId());
        log("DIRECTED_RETURN_REJECT", id, null);
        return Result.success();
    }

    /**
     * 原归属站：被指定水站退回、等待同意的订单列表
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-returns")
    public Result<?> getDirectedReturns() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(orderMapper.listDirectedReturns(stationId));
    }

    /**
     * 目标水站视角：被其他水站指定为履约站的订单列表（他站外派给我）
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-incoming")
    public Result<?> getDirectedIncoming() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(orderMapper.listDirectedIncoming(stationId));
    }

    /**
     * 站长拒单（简化版）：直接取消 或 尝试外派进入抢单池
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/station-reject")
    @Transactional
    public Result<Void> stationReject(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");

        String reason = params.get("reason") != null ? params.get("reason").toString() : "站长拒单";
        boolean tryDispatch = Boolean.TRUE.equals(params.get("tryDispatch"));

        if (tryDispatch) {
            // 进入抢单池：清空 delivery_station_id
            order.setDeliveryStationId(null);
            order.setDeliveryStaffId(null);
            order.setStatus(OrderStatus.PENDING);
            order.setSpecialNote(appendNote(order.getSpecialNote(),
                    " [外派] 站长拒单后外派，原因=" + reason + "，原归属站=" + stationId));
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.update(order);
            orderMapper.clearDispatchStation(id); // 放回抢单池：清空履约站+配送员
        } else {
            // 直接取消 — 先更新备注，再由 refundOrder 统一处理退款+置CANCELLED
            order.setSpecialNote(appendNote(order.getSpecialNote(),
                    " [拒单] " + reason));
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.update(order);
            paymentService.refundOrder(id, reason);
            // 给客户发送拒单提醒
            notifyCustomerRejected(order.getCustomerId(), id, reason);
        }

        log("STATION_REJECT", id, Map.of("reason", reason, "tryDispatch", tryDispatch));
        return Result.success();
    }

}
