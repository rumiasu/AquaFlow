package com.example.aquaflow.service;

import com.example.aquaflow.entity.Customer;

import java.util.List;

public interface CustomerService {
    List<Customer> list();

    void save(Customer customer);

    Customer getById(Integer id);

    void update(Customer customer);
}
