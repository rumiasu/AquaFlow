package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketRecord;

import java.util.List;

public interface TicketRecordService {

    List<TicketRecord> listByCustomerId(Long customerId);
}
