package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.exception.BusinessException;
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
            throw new BusinessException("客户不存在");
        }

        // ===== 方向与符号：单一真相源 =====
        // 约定：调用方一律传【正数】金额，方向完全由 type 决定（见 DepositType.isIncrease/isDecrease）。
        // 落库时增加记正、扣减记负 —— 对账等式1（balance == SUM(deposit_record.amount)）依赖这个符号。
        // [2026-09-13 修复] 旧实现对扣减类型直接落正数金额，账户减了、流水却是正的，
        // 于是每经这个入口做一次退款/扣减，对账就凭空多出一条差额；且未知 type 会静默落流水不动余额。
        Integer type = record.getType();
        BigDecimal amount = record.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("押金变动金额必须为正数，增减方向由流水类型决定");
        }

        if (DepositType.isIncrease(type)) {
            customerDepositAccountMapper.increaseBalance(record.getCustomerId(), stationId, amount);
            record.setAmount(amount);
        } else if (DepositType.isDecrease(type)) {
            int affected = customerDepositAccountMapper.decreaseBalance(record.getCustomerId(), stationId, amount);
            if (affected == 0) {
                throw new BusinessException("押金余额不足，无法完成本次押金变动");
            }
            record.setAmount(amount.negate());
        } else {
            throw new BusinessException("不支持的押金流水类型: " + type);
        }

        record.setStationId(stationId);
        record.setCreateTime(LocalDateTime.now());
        depositRecordMapper.insert(record);
    }
}
