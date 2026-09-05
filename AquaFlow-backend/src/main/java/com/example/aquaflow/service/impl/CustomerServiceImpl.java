package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
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

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Override
    public void update(Customer customer) {
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.update(customer);
    }

    @Override
    public Customer getById(Long id) {
        Customer customer = customerMapper.getById(id);
        if (customer == null) {
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
    public List<Customer> list(Long stationId) {
        if (stationId != null) {
            return customerMapper.listByStationId(stationId);
        }
        return customerMapper.list();
    }

    @Override
    public Map<String, Object> getCustomerStats(Long customerId) {
        Customer customer = customerMapper.getById(customerId);
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("firstOrderTime", customer.getFirstOrderTime());
        stats.put("lastDeliveryTime", customer.getLastDeliveryTime());
        return stats;
    }

    @Override
    public CustomerStationConfig getOfflinePaymentConfig(Long customerId, Long stationId) {
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        if (config == null) {
            // 确保记录存在，默认关闭
            customerStationConfigMapper.ensureExists(customerId, stationId);
            config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        }
        return config;
    }

    @Override
    public void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled) {
        customerStationConfigMapper.ensureExists(customerId, stationId);
        customerStationConfigMapper.updateOfflinePaymentEnabled(customerId, stationId, enabled);
    }
}
