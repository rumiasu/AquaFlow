package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.CustomerStationRecord;
import com.example.aquaflow.mapper.CustomerStationRecordMapper;
import com.example.aquaflow.service.CustomerStationRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerStationRecordServiceImpl implements CustomerStationRecordService {

    @Autowired
    private CustomerStationRecordMapper customerStationRecordMapper;

    @Override
    public List<CustomerStationRecord> listByCustomerId(Integer customerId) {
        return customerStationRecordMapper.listByCustomerId(customerId);
    }
}
