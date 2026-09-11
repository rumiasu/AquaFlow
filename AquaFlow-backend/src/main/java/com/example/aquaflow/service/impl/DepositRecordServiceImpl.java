package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
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

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Override
    public List<DepositRecord> listByCustomerAndStation(Long customerId, Long stationId) {
        return depositRecordMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(DepositRecord record, Long stationId) {
        Customer customer = customerMapper.getById(record.getCustomerId());
        if (customer == null) {
            throw new RuntimeException("客户不存在");
        }
        record.setStationId(stationId);
        record.setCreateTime(LocalDateTime.now());
        depositRecordMapper.insert(record);

        // 更新押金账户
        if (Integer.valueOf(1).equals(record.getType()) || Integer.valueOf(5).equals(record.getType())) {
            // 新增押金 / PREPAID(预收押金)
            customerDepositAccountMapper.increaseBalance(record.getCustomerId(), stationId, record.getAmount());
        } else if (Integer.valueOf(2).equals(record.getType()) || Integer.valueOf(3).equals(record.getType()) || Integer.valueOf(4).equals(record.getType())
                || Integer.valueOf(6).equals(record.getType()) || Integer.valueOf(7).equals(record.getType()) || Integer.valueOf(8).equals(record.getType())) {
            // 退押金 / 丢桶赔偿 / 其他调整 / RETURN_BARREL / EXCEPTION_COMPENSATION / CANCEL_PREPAID
            int affected = customerDepositAccountMapper.decreaseBalance(record.getCustomerId(), stationId, record.getAmount());
            if (affected == 0) {
                throw new RuntimeException("押金余额不足");
            }
        }
    }
}
