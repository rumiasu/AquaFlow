package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.service.TicketAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketAccountServiceImpl implements TicketAccountService {

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Override
    public List<TicketAccount> listByCustomerAndStation(Long customerId, Long stationId) {
        return ticketAccountMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    @Transactional
    public void addTicket(Long customerId, Long productId, Integer qty, Long stationId) {
        // 水票按水站隔离
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(qty);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        } else {
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(null);
        record.setSource("购买");
        record.setTicketSource(1);
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }

    @Override
    @Transactional
    public void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId) {
        // 水票按水站隔离
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            throw new RuntimeException("当前水站水票余额不足");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new RuntimeException("水票余额不足");
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(0);
        record.setDecreaseQty(qty);
        record.setOrderId(orderId);
        record.setSource("消费");
        record.setTicketSource(1);
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }

    @Override
    @Transactional
    public PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod, Long stationId) {
        // #30: 先创建PENDING支付记录，再入账水票（支付确认后再真正入账）
        Product product = productMapper.getById(productId);
        BigDecimal unitPrice = product != null ? product.getPrice() : BigDecimal.ZERO;
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(qty));

        PaymentRecord record = new PaymentRecord();
        record.setCustomerId(customerId);
        record.setStationId(stationId);
        record.setAmount(totalAmount);
        record.setPaymentMethod(paymentMethod);
        record.setStatus(PaymentStatus.PENDING);
        record.setNote("线上购买水票");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);

        // 水票入账延迟到支付确认后执行（这里先记录，实际应由支付回调触发addTicket）
        // TODO: 支付确认回调中调用 addTicket(customerId, productId, qty, stationId)

        return record;
    }
}
