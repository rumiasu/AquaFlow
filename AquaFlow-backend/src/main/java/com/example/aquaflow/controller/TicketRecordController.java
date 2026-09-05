package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket-records")
@Slf4j
public class TicketRecordController {

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    /**
     * 员工查询指定客户的水票流水（管理端）
     * GET /api/ticket-records/customer/{customerId}?stationId=xxx
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        return Result.success(ticketRecordMapper.listByCustomerIdWithDetail(customerId));
    }
}