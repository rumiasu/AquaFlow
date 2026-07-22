package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;

import java.util.List;
import java.util.Map;

public interface OrderService {
    void save(Orders orders);

    List<Orders> list(Integer stationId, Integer customerId, Integer status, String tag, String createTimeStart, String createTimeEnd);

    Orders getById(Integer id);

    void updateStatus(Integer id, Integer status);
}
