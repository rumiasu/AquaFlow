package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.TicketAddDTO;
import com.example.aquaflow.dto.TicketConsumeDTO;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
@Slf4j
public class TicketAccountController {

    @Autowired
    private TicketAccountService ticketAccountService;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @GetMapping
    public Result<List<Map<String, Object>>> listByCustomerId() {
        Integer customerId = AuthContext.requireCustomerId();
        return Result.success(ticketAccountMapper.listByCustomerIdWithDetail(customerId));
    }

    /**
     * 员工查询指定客户的水票（管理端）
     * GET /api/tickets/customer/{customerId}
     */
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Integer customerId) {
        AuthContext.requireStaffRole();
        return Result.success(ticketAccountMapper.listByCustomerIdWithDetail(customerId));
    }

    @PostMapping("/add")
    public Result add(@RequestBody TicketAddDTO dto) {
        AuthContext.requireStaffRole();
        ticketAccountService.addTicket(dto.getCustomerId(), dto.getWaterTypeId(), dto.getQuantity());
        return Result.success();
    }

    @PostMapping("/consume")
    public Result consume(@RequestBody TicketConsumeDTO dto) {
        AuthContext.requireStaffRole();
        ticketAccountService.consumeTicket(dto.getCustomerId(), dto.getWaterTypeId(), dto.getQuantity(), dto.getOrderId());
        return Result.success();
    }
}
