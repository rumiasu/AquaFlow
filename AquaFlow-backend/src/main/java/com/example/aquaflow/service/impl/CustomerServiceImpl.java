package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public void update(Customer customer) {
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);
    }

    @Override
    public Customer getById(Integer id) {
        Customer customer = customerMapper.getById(id);
        if(customer == null){
            throw new RuntimeException("客户不存在");
        }
        return customer;
    }

    @Override
    public void save(Customer customer) {
        customer.setCreateTime(LocalDateTime.now());
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.insert(customer);
    }

    @Override
    public List<Customer> list(Integer stationId) {
        if (stationId != null) {
            return customerMapper.listByStationId(stationId);
        }
        return customerMapper.list();
    }

    @Override
    public Map<String, Object> getCustomerStats(Integer customerId) {
        // 先刷新统计数据
        customerMapper.refreshStats(customerId);
        
        // 获取客户信息（包含统计字段）
        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalOrders", customer.getTotalOrders());
        stats.put("totalConsumption", customer.getTotalConsumption());
        stats.put("avgCycleDays", customer.getAvgCycleDays());
        stats.put("firstOrderTime", customer.getFirstOrderTime());
        stats.put("lastDeliveryTime", customer.getLastDeliveryTime());
        return stats;
    }
}
