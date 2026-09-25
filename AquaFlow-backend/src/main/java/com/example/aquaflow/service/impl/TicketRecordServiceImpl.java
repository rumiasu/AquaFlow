package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.TicketRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketRecordServiceImpl implements TicketRecordService {

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Override
    public List<TicketRecord> listByCustomerId(Long customerId) {
        return ticketRecordMapper.listByCustomerId(customerId);
    }
}
