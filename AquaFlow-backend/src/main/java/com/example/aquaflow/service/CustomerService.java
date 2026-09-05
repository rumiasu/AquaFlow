package com.example.aquaflow.service;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;

import java.util.List;
import java.util.Map;

public interface CustomerService {
    List<Customer> list(Long stationId);

    void save(Customer customer);

    Customer getById(Long id);

    void update(Customer customer);

    Map<String, Object> getCustomerStats(Long customerId);

    CustomerStationConfig getOfflinePaymentConfig(Long customerId, Long stationId);

    void updateOfflinePaymentConfig(Long customerId, Long stationId, Integer enabled);
}
