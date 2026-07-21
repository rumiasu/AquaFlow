package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Override
    @Transactional
    public PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                       BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                       Long ticketWaterTypeId, Integer ticketQty, String note) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setAmount(amount);
        record.setWaterAmount(waterAmount != null ? waterAmount : BigDecimal.ZERO);
        record.setBarrelDeposit(barrelDeposit != null ? barrelDeposit : BigDecimal.ZERO);
        record.setExcessBarrels(excessBarrels != null ? excessBarrels : 0);
        record.setPaymentMethod(paymentMethod);
        record.setTicketWaterTypeId(ticketWaterTypeId);
        record.setTicketQty(ticketQty);
        record.setNote(note);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());

        if (paymentMethod == 1 || paymentMethod == 2) {
            record.setStatus(1); // 待支付
        } else {
            record.setStatus(2); // 水票/挂账直接确认
        }

        paymentRecordMapper.insert(record);
        return record;
    }

    @Override
    @Transactional
    public void confirmPayment(Long paymentId) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new RuntimeException("支付记录不存在");
        if (record.getStatus() != 1) throw new RuntimeException("该记录状态异常");
        paymentRecordMapper.updateStatus(paymentId, 2);
    }

    @Override
    @Transactional
    public void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount) {
        List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
        for (PaymentRecord r : records) {
            if (r.getPaymentMethod() == 2 && r.getStatus() == 1) {
                paymentRecordMapper.updateStatus(r.getId(), 2);
                return;
            }
        }
        createPayment(orderId, customerId, amount, amount, BigDecimal.ZERO, 0, 2, null, null, "货到付款-现金");
    }

    @Override
    @Transactional
    public void lockTicketPayment(Long orderId, Long customerId, Long waterTypeId, int qty) {
        TicketAccount account = ticketAccountMapper.getByCustomerAndWaterType(customerId.intValue(), waterTypeId.intValue());
        if (account == null || account.getRemainQuantity() < qty) {
            throw new RuntimeException("水票余额不足");
        }
        ticketAccountMapper.decrementQuantity(account.getId(), qty);
    }

    @Override
    @Transactional
    public void deductTickets(Long orderId) {
        List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
        for (PaymentRecord r : records) {
            if (r.getPaymentMethod() == 3 && r.getStatus() == 2) {
                ticketRecordMapper.insertWaterTicketRecord(
                        r.getCustomerId().intValue(),
                        r.getTicketWaterTypeId().intValue(),
                        0, r.getTicketQty(),
                        orderId.intValue(), "order");
            }
        }
    }

    @Override
    @Transactional
    public void refundPayment(Long paymentId, String note) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new RuntimeException("支付记录不存在");
        if (record.getStatus() != 2) throw new RuntimeException("只能退款已支付记录");

        paymentRecordMapper.updateStatus(paymentId, 3);

        if (record.getPaymentMethod() == 3 && record.getTicketWaterTypeId() != null) {
            TicketAccount account = ticketAccountMapper.getByCustomerAndWaterType(
                    record.getCustomerId().intValue(), record.getTicketWaterTypeId().intValue());
            if (account != null) {
                ticketAccountMapper.incrementQuantity(account.getId(), record.getTicketQty());
            }
        }
    }

    @Override
    public List<PaymentRecord> listByOrderId(Long orderId) {
        return paymentRecordMapper.listByOrderId(orderId);
    }

    @Override
    public List<PaymentRecord> listByCustomerId(Long customerId) {
        return paymentRecordMapper.listByCustomerId(customerId);
    }

    @Override
    public List<PaymentRecord> listAll(int limit) {
        return paymentRecordMapper.listAll(limit);
    }

    @Override
    public Map<String, Object> getStationConfig(Long stationId) {
        Map<String, Object> config = new HashMap<>();
        config.put("enableWechat", true);
        config.put("enableCash", true);
        config.put("enableCod", true);
        config.put("enableTicketOnline", true);
        config.put("enableTicketOffline", true);
        config.put("enableCredit", false);
        config.put("enableMixed", false);
        return config;
    }

    @Override
    public void updateStationConfig(Long stationId, Map<String, Object> config) {
        // TODO: 更新 station_payment_config 表
    }
}
