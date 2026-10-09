package com.example.aquaflow.util;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import java.util.List;
import java.util.Objects;

/** Employee task reads use the refreshed binding and the same visibility gate as dispatch. */
public final class OrderTaskAccess {
    private OrderTaskAccess() {}

    public static boolean visibleToEmployee(Orders order) {
        if (order == null) return false;
        Integer state = order.getStatus();
        return Integer.valueOf(PaymentStatus.PAID).equals(order.getPaymentStatus())
                || Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod())
                || Integer.valueOf(OrderStatus.DELIVERED).equals(state)
                || Integer.valueOf(OrderStatus.COMPLETED).equals(state)
                || Integer.valueOf(OrderStatus.CANCELLED).equals(state);
    }

    public static void requireEmployeeDetail(Orders order) {
        if (!AuthContext.isManager() && !AuthContext.isDelivery()) {
            throw new BusinessException("仅已绑定水站的员工可查询任务");
        }
        Long station = AuthContext.requireStationId();
        if (!station.equals(StationUtil.deliveryStation(order))) {
            throw new BusinessException("无权查看他站订单");
        }
        if (AuthContext.isDelivery() && (order.getDeliveryStaffId() == null
                || !order.getDeliveryStaffId().equals(AuthContext.getUserId()))) {
            throw new BusinessException("仅可查看分配给自己的订单");
        }
        if (!visibleToEmployee(order)) {
            throw new BusinessException("该订单尚未支付，暂不可查看配送任务");
        }
        CustomerProfileMask.maskIfCrossStation(order);
    }

    /** Current active tasks require a binding; personal historical records keep their existing scope. */
    public static List<Orders> currentOwnTasks(List<Orders> orders) {
        Long station = AuthContext.requireStationId();
        Long staff = AuthContext.getUserId();
        if (staff == null) throw new BusinessException("无法识别当前员工");
        if (orders == null) return List.of();
        List<Orders> current = orders.stream()
                .filter(order -> order != null && Objects.equals(staff, order.getDeliveryStaffId())
                        && station.equals(StationUtil.deliveryStation(order)) && visibleToEmployee(order))
                .toList();
        current.forEach(CustomerProfileMask::maskIfCrossStation);
        return current;
    }
}
