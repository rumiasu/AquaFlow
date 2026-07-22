package com.example.aquaflow.service;

import com.example.aquaflow.entity.Customer;

import java.util.List;
import java.util.Map;

public interface CustomerService {
    List<Customer> list(Integer stationId);

    void save(Customer customer);

    Customer getById(Integer id);

    void update(Customer customer);

    Map<String, Object> getCustomerStats(Integer customerId);
}
