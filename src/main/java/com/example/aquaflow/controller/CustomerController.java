package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@Slf4j
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @GetMapping
    public Result<List<Customer>> list() {
        return Result.success(customerService.list());
    }

    @PostMapping
    public Result save(@RequestBody Customer customer){
        customerService.save(customer);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Customer> getById(@PathVariable Integer id){
        Customer customer = customerService.getById(id);
        return Result.success(customer);
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Customer customer){
        customer.setId(id);
        customerService.update(customer);
        return Result.success();
    }
}
