package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.service.DepositRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DepositRecordServiceImpl implements DepositRecordService {

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Override
    public List<DepositRecord> listByCustomerId(Integer customerId) {
        return depositRecordMapper.listByCustomerId(customerId);
    }

    @Override
    @Transactional
    public void add(DepositRecord record) {
        record.setCreateTime(LocalDateTime.now());
        depositRecordMapper.insert(record);

        Customer customer = customerMapper.getById(record.getCustomerId());
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }
        BigDecimal balance = customer.getDepositBalance() == null ? BigDecimal.ZERO : customer.getDepositBalance();
        if (record.getType() == 1) {
            customer.setDepositBalance(balance.add(record.getAmount()));
        } else if (record.getType() == 2 || record.getType() == 3 || record.getType() == 4) {
            customer.setDepositBalance(balance.subtract(record.getAmount()));
        }
        customerMapper.update(customer);
    }
}
