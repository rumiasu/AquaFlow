package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.mapper.OrderMapper;
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

    @Autowired
    private OrderMapper orderMapper;

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
            record.setStatus(PaymentStatus.PENDING);
        } else {
            record.setStatus(PaymentStatus.PAID); // 水票/挂账直接确认
        }

        paymentRecordMapper.insert(record);

        // 同步订单付款状态为待付款
        if (orderId != null) {
            orderMapper.updatePaymentStatus(orderId.intValue(), PaymentStatus.UNPAID);
        }

        return record;
    }

    @Override
    @Transactional
    public void confirmPayment(Long paymentId) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new RuntimeException("支付记录不存在");
        if (record.getStatus() != PaymentStatus.PENDING) throw new RuntimeException("该记录状态异常");
        paymentRecordMapper.updateStatus(paymentId, PaymentStatus.PAID);

        // 同步订单付款状态为已付款
        if (record.getOrderId() != null) {
            orderMapper.updatePaymentStatus(record.getOrderId().intValue(), PaymentStatus.PAID);
        }
    }

    @Override
    @Transactional
    public void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount) {
        List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
        for (PaymentRecord r : records) {
            if (r.getPaymentMethod() == 2 && r.getStatus() == PaymentStatus.PENDING) {
                paymentRecordMapper.updateStatus(r.getId(), PaymentStatus.PAID);
                // 同步订单付款状态
                orderMapper.updatePaymentStatus(orderId.intValue(), PaymentStatus.PAID);
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
            if (r.getPaymentMethod() == 3 && r.getStatus() == PaymentStatus.PAID) {
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
        if (record.getStatus() != PaymentStatus.PAID) throw new RuntimeException("只能退款已支付记录");

        paymentRecordMapper.updateStatus(paymentId, PaymentStatus.REFUNDED);

        // 水票退款：恢复水票余额
        if (record.getPaymentMethod() == 3 && record.getTicketWaterTypeId() != null) {
            TicketAccount account = ticketAccountMapper.getByCustomerAndWaterType(
                    record.getCustomerId().intValue(), record.getTicketWaterTypeId().intValue());
            if (account != null) {
                ticketAccountMapper.incrementQuantity(account.getId(), record.getTicketQty());
            }
        }

        // 同步订单付款状态回待付款
        if (record.getOrderId() != null) {
            // 检查该订单是否还有其他已付款记录
            List<PaymentRecord> allRecords = paymentRecordMapper.listByOrderId(record.getOrderId());
            boolean hasOtherPaid = allRecords.stream()
                    .anyMatch(r -> !r.getId().equals(paymentId) && r.getStatus() == PaymentStatus.PAID);
            if (!hasOtherPaid) {
                orderMapper.updatePaymentStatus(record.getOrderId().intValue(), PaymentStatus.UNPAID);
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
