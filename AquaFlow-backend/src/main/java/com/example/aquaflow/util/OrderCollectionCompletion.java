package com.example.aquaflow.util;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderMapper;

/** Call only inside the collection transaction, after payment is established and with the order row locked. */
public final class OrderCollectionCompletion {
    private OrderCollectionCompletion() {}
    public static void finishDelivered(OrderMapper orders, Long orderId, Integer lockedStatus) {
        if (Integer.valueOf(OrderStatus.DELIVERED).equals(lockedStatus)
                && orders.updateStatusIf(orderId, OrderStatus.DELIVERED, OrderStatus.COMPLETED) != 1)
            throw new BusinessException("订单状态已变更，请刷新后重试");
    }
}
