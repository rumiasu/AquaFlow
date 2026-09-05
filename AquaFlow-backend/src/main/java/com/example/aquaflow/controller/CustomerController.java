package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
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

    @Autowired
    private com.example.aquaflow.mapper.CustomerMapper customerMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<Customer>> list(@RequestParam(required = false) Long stationId) {
        if (AuthContext.isManager()) {
            stationId = AuthContext.requireStationId();
        }
        return Result.success(customerService.list(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result save(@RequestBody Customer customer){
        customerService.save(customer);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}")
    public Result<Customer> getById(@PathVariable Long id){
        // #37: 校验客户属于当前站长的水站
        Customer customer = customerService.getById(id);
        if (customer == null) {
            return Result.error("客户不存在");
        }
        Long stationId = AuthContext.requireStationId();
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(id, stationId);
        if (config == null) {
            return Result.error("无权查看其他水站的客户");
        }
        return Result.success(customer);
    }

    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody Customer customer){
        // #37: 校验客户属于当前站长的水站
        Customer existing = customerService.getById(id);
        if (existing == null) {
            return Result.error("客户不存在");
        }
        Long stationId = AuthContext.requireStationId();
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(id, stationId);
        if (config == null) {
            return Result.error("无权修改其他水站的客户");
        }
        customer.setId(id);
        customerService.update(customer);
        return Result.success();
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(customerService.getCustomerStats(customerId));
    }

    /** 获取客户在当前站长水站的线下支付授权状态 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/{id}/offline-payment")
    public Result<CustomerStationConfig> getOfflinePaymentConfig(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(customerService.getOfflinePaymentConfig(id, stationId));
    }

    /** 设置客户在当前站长水站的线下支付授权（仅当水站总开关开启时生效） */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/offline-payment")
    public Result updateOfflinePaymentConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long stationId = AuthContext.requireStationId();
        Integer enabled = body.get("offlinePaymentEnabled") != null ? Integer.valueOf(body.get("offlinePaymentEnabled").toString()) : 0;
        customerService.updateOfflinePaymentConfig(id, stationId, enabled);
        return Result.success();
    }
}