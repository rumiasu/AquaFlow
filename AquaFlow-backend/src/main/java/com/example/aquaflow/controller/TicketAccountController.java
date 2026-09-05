package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
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
    public Result<List<Map<String, Object>>> listByCustomerId(@RequestParam Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(ticketAccountMapper.listByCustomerAndStationWithDetail(customerId, stationId));
    }

    /**
     * 员工查询指定客户的水票（管理端）
     * GET /api/tickets/customer/{customerId}?stationId=xxx
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        return Result.success(ticketAccountMapper.listByCustomerAndStationWithDetail(customerId, stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/add")
    public Result add(@RequestBody TicketAddDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        if (dto.getStationId() == null) {
            return Result.error("水站ID不能为空");
        }
        Long stationId = dto.getStationId();
        ticketAccountService.addTicket(dto.getCustomerId(), dto.getProductId(), dto.getQuantity(), stationId);
        return Result.success();
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/consume")
    public Result consume(@RequestBody TicketConsumeDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        if (dto.getStationId() == null) {
            return Result.error("水站ID不能为空");
        }
        ticketAccountService.consumeTicket(dto.getCustomerId(), dto.getProductId(), dto.getQuantity(), dto.getOrderId(), dto.getStationId());
        return Result.success();
    }

    /**
     * 客户线上购买水票
     * POST /api/tickets/purchase  { productId, quantity, paymentMethod, stationId }
     */
    @PostMapping("/purchase")
    public Result<java.util.Map<String, Object>> purchase(@RequestBody TicketConsumeDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        if (dto.getProductId() == null || dto.getQuantity() == null) {
            return Result.error("参数不完整");
        }
        if (dto.getStationId() == null) {
            return Result.error("stationId 不能为空，请先选择服务水站");
        }
        Long stationId = dto.getStationId();
        com.example.aquaflow.entity.PaymentRecord pr = ticketAccountService.purchaseTicket(
                customerId, dto.getProductId(), dto.getQuantity(), dto.getPaymentMethod(), stationId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("paymentId", pr.getId());
        result.put("amount", pr.getAmount());
        result.put("status", pr.getStatus());
        return Result.success(result);
    }
}