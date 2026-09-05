package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.DepositDTO;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.DepositRecordService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/deposit-records")
@Slf4j
public class DepositRecordController {

    @Autowired
    private DepositRecordService depositRecordService;

    @Autowired
    private CustomerMapper customerMapper;

    @GetMapping
    public Result<List<DepositRecord>> listByCustomerId(@RequestParam(required = false) Long stationId) {
        Long customerId = AuthContext.requireCustomerId();
        if (stationId == null) {
            return Result.error("请选择水站");
        }
        return Result.success(depositRecordService.listByCustomerAndStation(customerId, stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<DepositRecord>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        return Result.success(depositRecordService.listByCustomerAndStation(customerId, stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result add(@RequestBody DepositDTO dto) {
        if (dto.getCustomerId() == null) {
            return Result.error("客户ID不能为空");
        }
        if (dto.getStationId() == null) {
            return Result.error("水站ID不能为空");
        }
        Long customerId = dto.getCustomerId() != null ? Long.valueOf(dto.getCustomerId()) : null;
        com.example.aquaflow.entity.Customer c = customerMapper.getById(customerId);
        if (c == null) return Result.error("客户不存在");
        DepositRecord record = new DepositRecord();
        record.setCustomerId(customerId);
        record.setType(dto.getType());
        record.setAmount(dto.getAmount());
        record.setNote(dto.getNote());
        Long stationId = dto.getStationId();
        depositRecordService.add(record, stationId);
        return Result.success();
    }
}