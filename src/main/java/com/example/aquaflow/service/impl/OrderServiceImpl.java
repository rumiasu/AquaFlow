package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void updateStatus(Integer id, Map<String, Integer> params) {
        orderMapper.updateStatus(id,params);
    }

    @Override
    public Orders getById(Integer id) {
        return orderMapper.getById(id);
    }

    @Override
    public List<Orders> list(Integer status, String tag, String createTimeStart, String createTimeEnd) {
        return orderMapper.list(status,tag,createTimeStart,createTimeEnd);
    }

    @Override
    public void save(Orders orders) {
        orderMapper.save(orders);
    }
}
