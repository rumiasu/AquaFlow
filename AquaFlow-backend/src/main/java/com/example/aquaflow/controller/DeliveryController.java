package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
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
import com.example.aquaflow.entity.CustomerNotification;
import com.example.aquaflow.service.PaymentService;
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

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelOwedMapper customerBarrelOwedMapper;

    @Autowired
    private com.example.aquaflow.mapper.CustomerNotificationMapper customerNotificationMapper;

    @Autowired
    private com.example.aquaflow.mapper.StationMapper stationMapper;

    private void checkStationOwnership(Orders order) {
        Long myStationId = AuthContext.getStationId();
        Long orderStation = deliveryStation(order);
        if (myStationId != null && orderStation != null && !myStationId.equals(orderStation)) {
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
        return Result.success(order);
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
        if (order.getPaymentMethod() == null || !Integer.valueOf(3).equals(order.getPaymentMethod())) {
            return Result.error("仅线下支付订单可确认收款");
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

        log("CONFIRM_OFFLINE_PAY", id, null);
        return Result.success();
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

        // 解析 itemReturns 数组（新版按商品核对回桶）
        int returnBucketQty = 0;
        List<Map<String, Object>> itemReturns = null;
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
                            returnBucketQty += ((Number) actual).intValue();
                        }
                    }
                }
            }
            // 兼容旧版 returnBucketQty 参数
            if (itemReturns == null && params != null && params.containsKey("returnBucketQty")) {
                Object rb = params.get("returnBucketQty");
                if (rb instanceof Number) {
                    returnBucketQty = ((Number) rb).intValue();
                }
            }
        }
        if (returnBucketQty < 0) return Result.error("回收空桶数不能为负数");
        Integer deliveryQty = order.getDeliveryBucketQty();
        int deliveryBucket = deliveryQty != null ? deliveryQty : 0;

        // #24: 查询客户欠桶记录，判断回桶上限
        int currentOwed = 0;
        if (!isFirstBarrelOrder) {
            CustomerBarrelOwed owedRecord = customerBarrelOwedMapper.get(order.getCustomerId(), order.getStationId());
            currentOwed = owedRecord != null ? owedRecord.getOwedQty() : 0;
        }
        // 欠桶信用额度：owed_qty为正=客户欠桶，为负=客户多还（有信用）
        int credit = Math.min(0, currentOwed); // 负数=可用额度，0=无额度
        int maxReturn = isFirstBarrelOrder ? deliveryBucket : deliveryBucket - credit;
        if (returnBucketQty > maxReturn) {
            if (credit < 0) {
                return Result.error("回收空桶数(" + returnBucketQty + ")超过上限(" + maxReturn + ")，含历史欠桶抵扣" + (-credit) + "桶");
            } else {
                return Result.error("回收空桶数(" + returnBucketQty + ")不能超过配送数(" + deliveryBucket + ")");
            }
        }
        order.setReturnBucketQty(returnBucketQty);

        int owed = isFirstBarrelOrder ? 0 : (deliveryBucket - returnBucketQty);
        order.setBarrelDiscrepancy(owed);

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

        // 货到付款判断：paymentMethod=2（现金/线下）或 paymentMethod=3（水票）且未线上支付
        Integer pm = order.getPaymentMethod();
        Integer ps = order.getPaymentStatus();
        boolean isCashOnDelivery = pm != null && !Integer.valueOf(1).equals(pm) && !(Integer.valueOf(2).equals(ps));
        boolean collected = false;
        if (params != null && params.containsKey("collected")) {
            Object c = params.get("collected");
            collected = Boolean.TRUE.equals(c);
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
            if (!collected) {
                order.setStatus(OrderStatus.DELIVERED);
                order.setPaymentStatus(PaymentStatus.UNPAID);
            } else {
                order.setStatus(OrderStatus.COMPLETED);
                order.setPaymentStatus(PaymentStatus.PAID);
            }
        } else {
            order.setStatus(OrderStatus.COMPLETED);
            order.setPaymentStatus(PaymentStatus.PAID);
        }

        // 更新在途桶资产状态为 DELIVERED，并转入持有桶资产
        if (order.getDeliveryBucketQty() != null && order.getDeliveryBucketQty() > 0) {
            List<CustomerBarrelInTransit> inTransitList = customerBarrelInTransitMapper.listPendingByOrderId(id);
            for (CustomerBarrelInTransit inTransit : inTransitList) {
                // 转入持有桶资产
                Long productId = inTransit.getProductId();
                int qty = inTransit.getQty() != null ? inTransit.getQty() : 0;
                if (productId != null && qty > 0) {
                    com.example.aquaflow.entity.CustomerBarrelAsset existing =
                        customerBarrelAssetMapper.getByCustomerAndProduct(
                            order.getCustomerId(), productId, order.getStationId());
                    if (existing == null) {
                        com.example.aquaflow.entity.CustomerBarrelAsset asset =
                            new com.example.aquaflow.entity.CustomerBarrelAsset();
                        asset.setCustomerId(order.getCustomerId());
                        asset.setProductId(productId);
                        asset.setStationId(order.getStationId());
                        asset.setQuantity(qty);
                        asset.setUpdateTime(LocalDateTime.now());
                        customerBarrelAssetMapper.insert(asset);
                    } else {
                        customerBarrelAssetMapper.increaseQuantity(existing.getId(), qty);
                    }
                }
            }
            customerBarrelInTransitMapper.updateStatusByOrderId(id, "DELIVERED");
        }

        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        // #24: 更新欠桶记录
        if (!isFirstBarrelOrder) {
            int deltaOwed = deliveryBucket - returnBucketQty; // 正=欠桶增加，负=多还抵扣
            if (deltaOwed != 0) {
                customerBarrelOwedMapper.adjustOwed(order.getCustomerId(), order.getStationId(), deltaOwed);
            }
        }

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
        if (AuthContext.isManager()) {
            Long stationId = AuthContext.requireStationId();
            if (!stationId.equals(deliveryStation(order))) return Result.error("无权操作他站订单");
        }
        String reason = params != null && params.get("reason") != null ? params.get("reason").toString() : "水站拒单";
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [拒单] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        // 触发退款（退支付记录 + 退押金账户 + 清在途桶），由 refundOrder 统一置为 CANCELLED
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
        order.setDeliveryStationId(targetStationId);
        order.setDeliveryStaffId(null); // 重新分配
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [外派] 从水站 " + myStationId + " 外派至 " + targetStationId + "，原因：" + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

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

        order.setStatus(OrderStatus.CANCELLED);
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
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [退回站长] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

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
    public Result<Void> outsourceOrder(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
        if (!stationId.equals(deliveryStation(order))) return Result.error("仅能操作本站订单");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可外派");
        // 清空 delivery_station_id 进入抢单池，station_id（归属站）不变
        order.setDeliveryStationId(null);
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [外派] 站长放入抢单池，原归属站=" + stationId));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        log("OUTSOURCE", id, Map.of("stationId", stationId));
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
        order.setDeliveryStaffId(staffId);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [认领]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
        log("CLAIM", id, null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/reject")
    public Result<Void> rejectTransfer(@PathVariable Long id) {
        Orders order = orderMapper.getById(id);
        if (order == null) return Result.error("订单不存在");
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
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [退回通过]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
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
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [退回拒绝]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);
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
        var pending = orderMapper.list(stationId, null, OrderStatus.PENDING, null, null);
        stats.put("pendingCount", pending.size());
        int totalReturn = completed.stream()
                .mapToInt(o -> o.getReturnBucketQty() != null ? o.getReturnBucketQty() : 0)
                .sum();
        stats.put("returnBarrels", totalReturn);

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

        // 更新订单：分配到本站
        order.setDeliveryStationId(stationId);
        order.setDeliveryStaffId(targetStaffId);
        order.setStatus(OrderStatus.DELIVERING);
        order.setSpecialNote(appendNote(order.getSpecialNote(),
                " [抢单] " + stationId + "站抢单成功，配送员=" + target.getName()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

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
        // 验证是本站外派的订单
        Long ownerStation = order.getStationId();
        if (!stationId.equals(ownerStation)) return Result.error("仅能取消本站外派的订单");
        if (order.getDeliveryStationId() != null) {
            return Result.error("该订单已被其他水站抢单，无法取消");
        }
        // 恢复为本站待分配
        order.setDeliveryStationId(stationId);
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [取消外派] 站长取消外派，恢复本站"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        log("CANCEL_DISPATCH", id, null);
        return Result.success();
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
