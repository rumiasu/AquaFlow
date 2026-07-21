package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.TicketAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketAccountServiceImpl implements TicketAccountService {

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Override
    public List<TicketAccount> listByCustomerId(Integer customerId) {
        return ticketAccountMapper.listByCustomerId(customerId);
    }

    @Override
    @Transactional
    public void addTicket(Integer customerId, Integer waterTypeId, Integer qty) {
        TicketAccount account = ticketAccountMapper.getByCustomerAndWaterType(customerId, waterTypeId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setWaterTypeId(waterTypeId);
            account.setRemainQuantity(qty);
            ticketAccountMapper.insert(account);
        } else {
            account.setRemainQuantity(account.getRemainQuantity() + qty);
            ticketAccountMapper.updateQuantity(account.getId(), account.getRemainQuantity());
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setWaterTypeId(waterTypeId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(null);
        record.setSource("购买");
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }

    @Override
    @Transactional
    public void consumeTicket(Integer customerId, Integer waterTypeId, Integer qty, Integer orderId) {
        TicketAccount account = ticketAccountMapper.getByCustomerAndWaterType(customerId, waterTypeId);
        if (account == null || account.getRemainQuantity() < qty) {
            throw new RuntimeException("水票余额不足");
        }
        account.setRemainQuantity(account.getRemainQuantity() - qty);
        ticketAccountMapper.updateQuantity(account.getId(), account.getRemainQuantity());

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setWaterTypeId(waterTypeId);
        record.setIncreaseQty(0);
        record.setDecreaseQty(qty);
        record.setOrderId(orderId);
        record.setSource("消费");
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }
}
