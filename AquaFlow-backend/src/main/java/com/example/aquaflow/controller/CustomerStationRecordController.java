package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CustomerStationRecord;
import com.example.aquaflow.service.CustomerStationRecordService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customer-station-records")
@Slf4j
public class CustomerStationRecordController {

    @Autowired
    private CustomerStationRecordService customerStationRecordService;

    @GetMapping
    public Result<List<CustomerStationRecord>> listByCustomerId() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(customerStationRecordService.listByCustomerId(customerId));
    }

    /** 员工查询指定客户的水站归属记录 */
    @GetMapping("/customer/{customerId}")
    public Result<List<CustomerStationRecord>> listByCustomerIdForStaff(@PathVariable Integer customerId) {
        AuthContext.requireStaffRole();
        return Result.success(customerStationRecordService.listByCustomerId(customerId));
    }
}
