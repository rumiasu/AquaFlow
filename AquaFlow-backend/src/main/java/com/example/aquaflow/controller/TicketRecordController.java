package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.TicketRecordService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket-records")
@Slf4j
public class TicketRecordController {

    @Autowired
    private TicketRecordService ticketRecordService;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @GetMapping
    public Result<List<Map<String, Object>>> listByCustomerId() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(ticketRecordMapper.listByCustomerIdWithDetail(customerId));
    }

    /**
     * 员工查询指定客户的水票流水（管理端）
     * GET /api/ticket-records/customer/{customerId}
     */
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Integer customerId) {
        AuthContext.requireStaffRole();
        return Result.success(ticketRecordMapper.listByCustomerIdWithDetail(customerId));
    }
}
