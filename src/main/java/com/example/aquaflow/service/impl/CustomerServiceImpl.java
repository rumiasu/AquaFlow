package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
        return customer;
    }

    @Override
    public void save(Customer customer) {
        customer.setCreateTime(LocalDateTime.now());
        customer.setUpdateTime(LocalDateTime.now());
        customerMapper.insert(customer);
    }

    @Override
    public List<Customer> list() {
        return customerMapper.list();
    }
}
