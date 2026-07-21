package com.example.aquaflow.service;

import com.example.aquaflow.entity.CustomerStationRecord;

import java.util.List;

public interface CustomerStationRecordService {

    List<CustomerStationRecord> listByCustomerId(Integer customerId);
}
