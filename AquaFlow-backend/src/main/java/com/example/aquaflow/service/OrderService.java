package com.example.aquaflow.service;

import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.Orders;

import java.util.List;
import java.util.Map;

public interface OrderService {

    OrderCreateResult createOrder(OrderCreateDTO dto);

    void save(Orders orders);

    List<Orders> list(Long stationId, Long customerId, Integer status, String createTimeStart, String createTimeEnd,
                      Integer limit, Integer offset);

    Orders getById(Long id);

    void updateStatus(Long id, Integer status);

    /**
     * 状态机驱动的状态转换（AQ-014）：先校验 OrderStatus.isValidTransition(当前, 目标)，
     * 再用 CAS 更新（where status = 当前值）。非法跳转或状态已被并发修改时抛 BusinessException。
     * 替代任意直写 orderMapper.updateStatus，杜绝 PENDING 直接跳 COMPLETED 之类的越级跳转。
     */
    void transitionStatus(Long id, Integer targetStatus);

    /** 支付状态 CAS 转换：仅当当前支付态等于 expected 时才更新为 target（返回 affected=0 视为并发冲突抛异常）。 */
    void transitionPaymentStatus(Long id, Integer expected, Integer target);

    /**
     * 客户主动取消订单（仅"待配送"可取消）。
     * 会完整回滚下单产生的副作用：回补库存、退还预收桶押金、清理配送中桶记录。
     *
     * @param orderId    订单ID
     * @param customerId 当前登录客户ID（用于校验归属，防止取消他人订单）
     */
    void cancelByCustomer(Long orderId, Long customerId);
}
