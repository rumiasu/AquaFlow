package com.example.aquaflow.service;

import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.Orders;

import java.util.List;
import java.util.Map;

public interface OrderService {

    OrderCreateResult createOrder(OrderCreateDTO dto);

    void save(Orders orders);

    List<Orders> list(Long stationId, Long customerId, Integer status, String createTimeStart, String createTimeEnd);

    Orders getById(Long id);

    void updateStatus(Long id, Integer status);

    /**
     * 客户主动取消订单（仅"待配送"可取消）。
     * 会完整回滚下单产生的副作用：回补库存、退还预收桶押金、清理在途桶记录。
     *
     * @param orderId    订单ID
     * @param customerId 当前登录客户ID（用于校验归属，防止取消他人订单）
     */
    void cancelByCustomer(Long orderId, Long customerId);
}
