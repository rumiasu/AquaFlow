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
}
