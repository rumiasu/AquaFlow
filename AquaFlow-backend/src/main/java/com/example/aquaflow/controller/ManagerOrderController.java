package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/manager")
public class ManagerOrderController {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private AuditLogService auditLogService;

    private Long deliveryStation(Orders o) {
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    private String appendNote(String existing, String part) {
        if (part == null || part.trim().isEmpty()) {
            return existing;
        }
        return (existing != null && !existing.trim().isEmpty()) ? existing + " " + part.trim() : part.trim();
    }

    private void logOrder(String action, Long orderId, Map<String, Object> detail) {
        auditLogService.log("ORDER", action, "order:" + orderId, detail != null ? detail.toString() : "", null);
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/assign")
    @Transactional
    public Result<Void> assignOrder(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if (!myStationId.equals(deliveryStation(order))) {
            return Result.error("仅能分配本站履约的订单");
        }
        Object staffIdObj = params.get("deliveryStaffId");
        if (staffIdObj == null) {
            return Result.error("请指定配送员");
        }
        Long deliveryStaffId = ((Number) staffIdObj).longValue();
        Staff target = staffMapper.getById(deliveryStaffId);
        if (target == null || !"DELIVERY".equals(target.getRole()) || target.getStatus() == null || !Integer.valueOf(1).equals(target.getStatus())) {
            return Result.error("目标不是在职配送员");
        }
        if (target.getStationId() == null || !target.getStationId().equals(myStationId)) {
            return Result.error("只能分配给本站配送员");
        }

        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING) {
            return Result.error("仅待分配订单可分配");
        }

        String fromName = "未分配";
        if (order.getDeliveryStaffId() != null) {
            Staff from = staffMapper.getById(order.getDeliveryStaffId());
            if (from != null) fromName = from.getName();
        }

        order.setDeliveryStaffId(deliveryStaffId);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [分配] 由 " + fromName + " 转给 " + target.getName()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", id);
        d.put("fromStaff", fromName);
        d.put("toStaffId", deliveryStaffId);
        d.put("toStaffName", target.getName());
        logOrder("ASSIGN", id, d);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/dispatch")
    @Transactional
    public Result<Void> dispatchOrder(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Orders order = orderMapper.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if (!myStationId.equals(deliveryStation(order))) {
            return Result.error("仅能派本站履约的订单");
        }
        if (order.getDeliveryStaffId() == null) {
            return Result.error("请先分配配送员");
        }
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) {
            return Result.error("当前状态不可派单出发");
        }

        order.setStatus(OrderStatus.DELIVERING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [派单出发]"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        logOrder("DISPATCH", id, Collections.singletonMap("deliveryStaffId", order.getDeliveryStaffId()));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer-apply")
    @Transactional
    public Result<Void> transferApply(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        Object targetStaffIdObj = params.get("targetStaffId");
        if (orderIdObj == null || targetStaffIdObj == null) {
            return Result.error("orderId、targetStaffId 必填");
        }
        Long orderId = ((Number) orderIdObj).longValue();
        Long targetStaffId = ((Number) targetStaffIdObj).longValue();
        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可转让");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可转让");

        Staff target = staffMapper.getById(targetStaffId);
        if (target == null || !"DELIVERY".equals(target.getRole()) || target.getStatus() == null || !Integer.valueOf(1).equals(target.getStatus())) return Result.error("目标不是在职配送员");
        if (target.getStationId() == null || !target.getStationId().equals(myStationId)) return Result.error("只能转给本站配送员");

        String reason = params.get("reason") != null ? params.get("reason").toString() : "站长发起转让";
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [转让申请] -> " + target.getName() + " 原因: " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", orderId);
        d.put("targetStaffId", targetStaffId);
        d.put("targetStaffName", target.getName());
        d.put("reason", reason);
        logOrder("TRANSFER_APPLY", orderId, d);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer-approve")
    @Transactional
    public Result<Void> transferApprove(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        Object targetStaffIdObj = params.get("targetStaffId");
        if (orderIdObj == null || targetStaffIdObj == null) return Result.error("orderId、targetStaffId 必填");
        Long orderId = ((Number) orderIdObj).longValue();
        Long targetStaffId = ((Number) targetStaffIdObj).longValue();

        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可操作");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可转让");

        Staff target = staffMapper.getById(targetStaffId);
        if (target == null || !"DELIVERY".equals(target.getRole()) || target.getStatus() == null || !Integer.valueOf(1).equals(target.getStatus())) return Result.error("目标不是在职配送员");
        if (target.getStationId() == null || !target.getStationId().equals(myStationId)) return Result.error("只能转给本站配送员");

        String fromName = order.getDeliveryStaffId() != null ? Optional.ofNullable(staffMapper.getById(order.getDeliveryStaffId())).map(Staff::getName).orElse("未分配") : "未分配";
        // #15: 先记录原始staffId再更新
        Long originalStaffId = order.getDeliveryStaffId();
        order.setDeliveryStaffId(targetStaffId);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [转让通过] 由 " + fromName + " 转到 " + target.getName()));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        Map<String, Object> d = new HashMap<>();
        d.put("orderId", orderId);
        d.put("fromStaffId", originalStaffId);
        d.put("toStaffId", targetStaffId);
        d.put("toStaffName", target.getName());
        logOrder("TRANSFER_APPROVE", orderId, d);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer-reject")
    @Transactional
    public Result<Void> transferReject(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        if (orderIdObj == null) return Result.error("orderId 必填");
        Long orderId = ((Number) orderIdObj).longValue();
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";

        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可操作");

        order.setSpecialNote(appendNote(order.getSpecialNote(), " [转让驳回] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        logOrder("TRANSFER_REJECT", orderId, Collections.singletonMap("reason", reason));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return-request")
    @Transactional
    public Result<Void> returnRequest(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        if (orderIdObj == null) return Result.error("orderId 必填");
        Long orderId = ((Number) orderIdObj).longValue();
        String reason = params.get("reason") != null ? params.get("reason").toString() : "申请退回站长";

        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可操作");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可申请退回");

        order.setSpecialNote(appendNote(order.getSpecialNote(), " [退回申请] " + reason));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        logOrder("RETURN_REQUEST", orderId, Collections.singletonMap("reason", reason));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return-approve")
    @Transactional
    public Result<Void> returnApprove(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        if (orderIdObj == null) return Result.error("orderId 必填");
        Long orderId = ((Number) orderIdObj).longValue();

        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可操作");
        int cur = order.getStatus() != null ? order.getStatus() : 0;
        if (cur != OrderStatus.PENDING && cur != OrderStatus.DELIVERING) return Result.error("当前状态不可退回");

        String fromName = order.getDeliveryStaffId() != null ? Optional.ofNullable(staffMapper.getById(order.getDeliveryStaffId())).map(Staff::getName).orElse("") : "";
        // #15: 先记录原始staffId再清空
        Long originalStaffId = order.getDeliveryStaffId();
        order.setDeliveryStaffId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setSpecialNote(appendNote(order.getSpecialNote(), " [退回通过] 由配送员 " + fromName + " 退回站长"));
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.update(order);

        logOrder("RETURN_APPROVE", orderId, Collections.singletonMap("fromStaffId", originalStaffId));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/offline-exception")
    @Transactional
    public Result<Void> offlineException(@RequestBody Map<String, Object> params) {
        Long myStationId = AuthContext.requireStationId();
        Object orderIdObj = params.get("orderId");
        Object actionObj = params.get("action");
        if (orderIdObj == null || actionObj == null) return Result.error("orderId、action 必填");
        Long orderId = ((Number) orderIdObj).longValue();
        String action = actionObj.toString();
        String note = params.get("note") != null ? params.get("note").toString() : "";

        Orders order = orderMapper.getById(orderId);
        if (order == null) return Result.error("订单不存在");
        if (!myStationId.equals(deliveryStation(order))) return Result.error("仅本站订单可操作");

        Map<String, Object> d = new HashMap<>();
        d.put("action", action);
        d.put("note", note);

        switch (action) {
            case "CONFIRM_COLLECTED":
                if (order.getStatus() != OrderStatus.DELIVERED) return Result.error("仅已配送待付款订单可确认收款");
                order.setPaymentStatus(PaymentStatus.PAID);
                order.setStatus(OrderStatus.COMPLETED);
                order.setSpecialNote(appendNote(order.getSpecialNote(), " [线下异常:确认收款] " + note));
                order.setUpdateTime(LocalDateTime.now());
                orderMapper.update(order);
                logOrder("OFFLINE_CONFIRM_COLLECTED", orderId, d);
                break;
            case "MARK_CANCELLED":
                if (order.getStatus() == OrderStatus.COMPLETED) return Result.error("已完成订单不可取消");
                order.setStatus(OrderStatus.CANCELLED);
                order.setSpecialNote(appendNote(order.getSpecialNote(), " [线下异常:取消] " + note));
                order.setUpdateTime(LocalDateTime.now());
                orderMapper.update(order);
                logOrder("OFFLINE_CANCEL", orderId, d);
                break;
            case "CORRECT_PAYMENT":
                Object pm = params.get("paymentMethod");
                if (pm != null) {
                    order.setPaymentMethod(((Number) pm).intValue());
                }
                Object ps = params.get("paymentStatus");
                if (ps != null) {
                    order.setPaymentStatus(((Number) ps).intValue());
                }
                order.setSpecialNote(appendNote(order.getSpecialNote(), " [线下异常:修正支付] " + note));
                order.setUpdateTime(LocalDateTime.now());
                orderMapper.update(order);
                logOrder("OFFLINE_CORRECT", orderId, d);
                break;
            default:
                return Result.error("未知 action: " + action);
        }
        return Result.success();
    }
}
