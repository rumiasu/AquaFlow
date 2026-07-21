package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Override
    public void updateStatus(Integer id, Integer status) {
        orderMapper.updateStatus(id,status);
    }

    @Override
    public Orders getById(Integer id) {
        return orderMapper.getById(id);
    }

    @Override
    public List<Orders> list(Integer customerId, Integer status, String tag, String createTimeStart, String createTimeEnd) {
        return orderMapper.list(customerId, status, tag, createTimeStart, createTimeEnd);
    }

    @Override
    public void save(Orders orders) {
        orders.setStatus(OrderStatus.PENDING);
        orders.setCreateTime(LocalDateTime.now());
        orders.setUpdateTime(LocalDateTime.now());

        // 保存地址快照
        if (orders.getAddressId() != null) {
            Address addr = addressMapper.getById(orders.getAddressId());
            if (addr != null) {
                orders.setReceiverName(addr.getName());
                orders.setReceiverPhone(addr.getPhone());
                orders.setAddressSnapshot(addr.getDetail());
            }
        }

        orderMapper.save(orders);
    }
}
