package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.service.CustomerService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@Slf4j
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @GetMapping
    public Result<List<Customer>> list(@RequestParam(required = false) Integer stationId) {
        return Result.success(customerService.list(stationId));
    }

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
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

    @RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result update(@PathVariable Integer id, @RequestBody Customer customer){
        customer.setId(id);
        customerService.update(customer);
        return Result.success();
    }

    /**
     * 获取当前登录客户的统计数据
     * GET /api/customers/stats
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(customerService.getCustomerStats(customerId));
    }
}
