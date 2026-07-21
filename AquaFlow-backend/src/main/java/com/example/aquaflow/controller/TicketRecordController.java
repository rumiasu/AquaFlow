package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.TicketRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Result<List<Map<String, Object>>> listByCustomerId(@RequestParam Integer customerId) {
        return Result.success(ticketRecordMapper.listByCustomerIdWithDetail(customerId));
    }
}
