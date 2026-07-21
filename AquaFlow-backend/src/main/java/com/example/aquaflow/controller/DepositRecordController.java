package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.DepositDTO;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.service.DepositRecordService;
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

    @GetMapping
    public Result<List<DepositRecord>> listByCustomerId(@RequestParam Integer customerId) {
        return Result.success(depositRecordService.listByCustomerId(customerId));
    }

    @PostMapping
    public Result add(@RequestBody DepositDTO dto) {
        DepositRecord record = new DepositRecord();
        BeanUtils.copyProperties(dto, record);
        depositRecordService.add(record);
        return Result.success();
    }
}
