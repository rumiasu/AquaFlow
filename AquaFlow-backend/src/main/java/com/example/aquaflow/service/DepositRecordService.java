package com.example.aquaflow.service;

import com.example.aquaflow.entity.DepositRecord;

import java.util.List;

public interface DepositRecordService {

    List<DepositRecord> listByCustomerId(Integer customerId);

    void add(DepositRecord record);
}
