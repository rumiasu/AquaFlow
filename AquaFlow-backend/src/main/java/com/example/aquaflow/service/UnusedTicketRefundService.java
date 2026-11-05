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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 有购票原款凭据的剩余批次退出；赠票、推断价、无来源回补批次走人工裁决。
 *
 * <p>同一原购票款可以多次退出：部分退出后订单售后把已用票回补到原批次，仍可按原款继续退出。
 * 每次退出在<b>原款行锁</b>内累计数量和金额，累计不超过原款；最后一张补齐批次尾差。
 * 客户端请求键只用于重试幂等，不能替代余额与累计上限校验。</p>
 */
@Service
public class UnusedTicketRefundService {
    @Autowired private TicketDeliveryBenefitService deliveryBenefits;
    @Autowired private PaymentRecordMapper payments;
    @Autowired private ConsumptionRefundMapper locks;
    @Autowired private TicketLotService lots;
    @Autowired private TicketRecordMapper records;
    @Autowired private TicketExitReceiptMapper receipts;
    @Autowired private com.example.aquaflow.payment.MockPaymentPolicy mockPolicy;
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
    public void refund(Long paymentId,String note,Integer expectedQty,BigDecimal expectedAmount,String idempotencyKey) {
        if(expectedQty==null || expectedAmount==null) throw new BusinessException("请先核实并确认本批剩余水票数量和实退金额");
        if(idempotencyKey==null || idempotencyKey.isBlank()) throw new BusinessException("请重新打开退票批次后再提交");
        if(idempotencyKey.length()>64) throw new BusinessException("退票请求凭据过长");
        PaymentRecord p=locks.lockPayment(paymentId);
        if (p==null || p.getOrderId()!=null || p.getTicketQty()==null || p.getTicketQty()<=0
                || !AuthContext.requireStationId().equals(p.getStationId())) throw new BusinessException("仅购票原收款站可办理本批剩余票退款");
        // 同键重试必须返回原结果：先查凭据再判原款状态，否则最后一次退出成功后重试会被“已办理”误拒。
        Map<String,Object> prior=receipts.findByRequestKey(paymentId,idempotencyKey);
        if(prior!=null) {
            int priorQty=((Number)prior.get("quantity")).intValue();
            BigDecimal priorAmount=decimal(prior.get("amount"));
            if(priorQty==expectedQty && priorAmount.compareTo(expectedAmount)==0) return;
            throw new BusinessException("同一退票请求凭据已用于其他数量或金额，请刷新后重新确认");
        }
        if (!Integer.valueOf(PaymentStatus.PAID).equals(p.getStatus())) throw new BusinessException("本批未付款或已办理退票");
        int refundedQty=0;
        BigDecimal refundedAmount=BigDecimal.ZERO;
        // 当前读累计凭据：原款行锁只能挡住同一原款的退出，普通 SELECT 在 RR 快照下
        // 会看不到并发事务刚提交的凭据，从而把同一请求键误判成“还没退过”。
        for(Map<String,Object> row:receipts.refundRowsForUpdate(paymentId)) {
            refundedQty+=((Number)row.get("quantity")).intValue();
            refundedAmount=refundedAmount.add(decimal(row.get("amount")));
        }
        BigDecimal originalAmount=nz(p.getAmount()).setScale(2,RoundingMode.HALF_UP);
        refundedAmount=refundedAmount.setScale(2,RoundingMode.HALF_UP);
        boolean amountExhausted=originalAmount.signum()>0 && refundedAmount.compareTo(originalAmount)>=0;
        if(refundedQty>=p.getTicketQty() || amountExhausted) throw new BusinessException("本批剩余票已全部退出，不能重复退款");
        mockPolicy.requireRefund(p);
        TicketLotService.ConsumeResult consumed=lots.consumePurchasedBalance(p.getCustomerId(),p.getStationId(),p.getTicketWaterTypeId(),paymentId);
        deliveryBenefits.revokeOnUnusedTicketExit(paymentId);
        int newTotalQty=refundedQty+consumed.getQuantity();
        if(newTotalQty>p.getTicketQty()) throw new BusinessException("本批退出数量超过原购票数量，请核实后处理");
        BigDecimal remainingOriginal=originalAmount.subtract(refundedAmount).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
        BigDecimal batchValue=nz(consumed.getTotalAmount()).setScale(2,RoundingMode.HALF_UP);
        // 累计数量达到原购票数量时按原款余额补齐尾差；未达到时只退当前批次实付价值且不超过原款余额。
        BigDecimal amount=(newTotalQty==p.getTicketQty()?remainingOriginal:batchValue.min(remainingOriginal))
                .max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
        if(consumed.getQuantity()!=expectedQty || amount.compareTo(expectedAmount)!=0)
            throw new BusinessException("水票余额或退款金额已变化，请刷新后重新确认；本次未扣票未登记退款");
        TicketRecord ticket=new TicketRecord(); ticket.setCustomerId(p.getCustomerId()); ticket.setStationId(p.getStationId());
        ticket.setProductId(p.getTicketWaterTypeId()); ticket.setSource("退出退票"); ticket.setIncreaseQty(0); ticket.setDecreaseQty(consumed.getQuantity());
        ticket.setUnitPrice(consumed.getWeightedUnitPrice()); ticket.setTicketLotId(consumed.getSingleLotId()); ticket.setCreateTime(LocalDateTime.now());
        records.insert(ticket);
        PaymentRecord r=new PaymentRecord(); r.setCustomerId(p.getCustomerId()); r.setStationId(p.getStationId()); r.setAmount(amount.negate());
        r.setWaterAmount(amount.negate()); r.setBarrelDeposit(BigDecimal.ZERO); r.setPaymentMethod(p.getPaymentMethod()); r.setStatus(PaymentStatus.REFUNDED);
        r.setTicketQty(-consumed.getQuantity()); r.setTicketWaterTypeId(p.getTicketWaterTypeId()); r.setOperatorId(AuthContext.getUserId());
        mockPolicy.markRefund(p,r);
        r.setNote("退出服务，退本批未使用水票；原款 "+paymentId+"；累计 "+newTotalQty+"/"+p.getTicketQty()+" 张");
        r.setCreateTime(LocalDateTime.now()); r.setUpdateTime(LocalDateTime.now()); payments.insert(r);
        if (receipts.insert(paymentId,r.getId(),idempotencyKey,consumed.getQuantity(),amount,AuthContext.getUserId())!=1) throw new BusinessException("退票凭据未保存");
        if (newTotalQty==p.getTicketQty() && payments.updateStatusTo(paymentId,PaymentStatus.REFUNDED,PaymentStatus.PAID)!=1) throw new BusinessException("原款状态已变更");
    }
    private static BigDecimal nz(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
    private static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
}
