package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 有购票原款凭据的剩余批次退出；赠票、推断价、无来源回补批次走人工裁决。 */
@Service
public class UnusedTicketRefundService {
    @Autowired private PaymentRecordMapper payments;
    @Autowired private ConsumptionRefundMapper locks;
    @Autowired private TicketLotService lots;
    @Autowired private TicketRecordMapper records;
    @Autowired private TicketExitReceiptMapper receipts;
    @Value("${app.payment.mock-wechat-pay:false}") private boolean mockWechatPay;
    public java.util.List<java.util.Map<String,Object>> candidates(Long station) {
        java.util.List<java.util.Map<String,Object>> rows=receipts.candidates(station);
        for(var row:rows) row.put("paymentMethodText",PayMethod.textOf(((Number)row.get("paymentMethod")).intValue()));
        return rows;
    }
    public java.util.Map<String,Object> preview(Long paymentId) {
        return candidates(AuthContext.requireStationId()).stream().filter(r->((Number)r.get("paymentId")).longValue()==paymentId)
            .findFirst().orElseThrow(()->new BusinessException("本批没有可按原款退回的剩余票，赠票和无来源批次须人工核实"));
    }
    @Transactional
    public void refund(Long paymentId,String note,Integer expectedQty,BigDecimal expectedAmount) {
        if(expectedQty==null || expectedAmount==null) throw new BusinessException("请先核实并确认本批剩余票数量和实退金额");
        PaymentRecord p=locks.lockPayment(paymentId);
        if (p==null || p.getOrderId()!=null || p.getTicketQty()==null || p.getTicketQty()<=0
                || !AuthContext.requireStationId().equals(p.getStationId())) throw new BusinessException("仅购票原收款站可办理本批剩余票退款");
        if (!Integer.valueOf(PaymentStatus.PAID).equals(p.getStatus()) || receipts.exists(paymentId)>0) throw new BusinessException("本批未付款或已办理退票");
        if (p.getPaymentMethod()==PayMethod.WECHAT && !mockWechatPay) throw new BusinessException("微信退款渠道未接入，不能登记为已自动退票退款");
        TicketLotService.ConsumeResult consumed=lots.consumePurchasedBalance(p.getCustomerId(),p.getStationId(),p.getTicketWaterTypeId(),paymentId);
        BigDecimal amount=(consumed.getQuantity()==p.getTicketQty()?p.getAmount():consumed.getTotalAmount().min(p.getAmount())).setScale(2,java.math.RoundingMode.HALF_UP);
        if(consumed.getQuantity()!=expectedQty || amount.compareTo(expectedAmount)!=0)
            throw new BusinessException("水票余额或退款金额已变化，请刷新后重新确认；本次未扣票未登记退款");
        TicketRecord ticket=new TicketRecord(); ticket.setCustomerId(p.getCustomerId()); ticket.setStationId(p.getStationId());
        ticket.setProductId(p.getTicketWaterTypeId()); ticket.setSource("退出退票"); ticket.setIncreaseQty(0); ticket.setDecreaseQty(consumed.getQuantity());
        ticket.setUnitPrice(consumed.getWeightedUnitPrice()); ticket.setTicketLotId(consumed.getSingleLotId()); ticket.setCreateTime(LocalDateTime.now());
        records.insert(ticket);
        PaymentRecord r=new PaymentRecord(); r.setCustomerId(p.getCustomerId()); r.setStationId(p.getStationId()); r.setAmount(amount.negate());
        r.setWaterAmount(amount.negate()); r.setBarrelDeposit(BigDecimal.ZERO); r.setPaymentMethod(p.getPaymentMethod()); r.setStatus(PaymentStatus.REFUNDED);
        r.setTicketQty(-consumed.getQuantity()); r.setTicketWaterTypeId(p.getTicketWaterTypeId()); r.setOperatorId(AuthContext.getUserId());
        r.setNote("退出服务，退本批未使用水票；原款 "+paymentId); r.setCreateTime(LocalDateTime.now()); r.setUpdateTime(LocalDateTime.now()); payments.insert(r);
        if (receipts.insert(paymentId,r.getId(),consumed.getQuantity(),amount,AuthContext.getUserId())!=1) throw new BusinessException("退票凭据未保存");
        if (consumed.getQuantity()==p.getTicketQty() && payments.updateStatusTo(paymentId,PaymentStatus.REFUNDED,PaymentStatus.PAID)!=1) throw new BusinessException("原款状态已变更");
    }
}
