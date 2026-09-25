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

/**
 * 水票流水：顾客查自己的（无注解 + {@code requireCustomerId()}），
 * 站长按客户查询（{@code STATION_MANAGER}）。
 *
 * <p>水票<b>余额</b>的真相源是 {@code ticket_account}，本类只读流水，不做余额计算。</p>
 */
@RestController
@RequestMapping("/api/ticket-records")
@Slf4j
public class TicketRecordController {

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    /**
     * 客户查询自己的水票流水。
     * GET /api/ticket-records?stationId=xxx
     * <p>customerId 从 JWT 获取，不接受前端传入。stationId 可选：传了只返回该站流水
     * （资产按水站隔离，页面切站后应看到本站记录），不传则返回全部。</p>
     */
    @GetMapping
    public Result<List<Map<String, Object>>> listByCustomerId(
            @RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        if (stationId != null) {
            return Result.success(ticketRecordMapper.listByCustomerAndStationWithDetail(customerId, stationId));
        }
        return Result.success(ticketRecordMapper.listByCustomerIdWithDetail(customerId));
    }

    /**
     * 员工查询指定客户的水票流水（管理端）
     * GET /api/ticket-records/customer/{customerId}?stationId=xxx
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<Map<String, Object>>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        // [AQ-023] 强制使用登录站长所属水站，忽略客户端传入的 stationId，杜绝跨站查询他站客户水票流水
        Long myStationId = AuthContext.requireStationId();
        return Result.success(ticketRecordMapper.listByCustomerAndStationWithDetail(customerId, myStationId));
    }
}