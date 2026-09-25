package com.example.aquaflow.service;

import com.example.aquaflow.entity.DepositRecord;

import java.util.List;

public interface DepositRecordService {

    List<DepositRecord> listByCustomerAndStation(Long customerId, Long stationId);

    void add(DepositRecord record, Long stationId);
}
