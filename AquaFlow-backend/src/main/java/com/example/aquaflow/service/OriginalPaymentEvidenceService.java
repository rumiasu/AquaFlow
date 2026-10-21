package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.payment.*;
import com.example.aquaflow.payment.wechat.WechatPaySuccessMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 可信原时间及未知原请求保全，无HTTP/商户解析/渠道调用；不确认收款或操作客户资产。 */
@Service @RequiredArgsConstructor
public class OriginalPaymentEvidenceService {
    private final OriginalPaymentEvidenceMapper mapper;
    private final PaymentRecordMapper payments;
    private final ConsumptionRefundMapper locks;
    private Clock clock=Clock.systemUTC();
    public Instant now(){return clock.instant();}
    public record Routing(String channel,boolean cashAllowed,BigDecimal remaining,String reason,boolean expired) {}

    /** 仅未来已验签/匹配的服务端适配器调用。缺时间/实付不造证据；不提供客户端补录入口。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean captureVerified(PaymentTransactionIdentity identity,WechatPaySuccessMatcher.MatchedPayment matched) {
        if(identity==null || matched==null)throw rejected();var expected=matched.expected();
        if(identity.paymentRecordId()!=expected.paymentRecordId() || !Objects.equals(identity.orderId(),expected.orderId())
                || !identity.outTradeNo().equals(expected.outTradeNo()) || !identity.amountYuan().equals(expected.amountYuan().setScale(2))
                || !identity.currency().equals(expected.currency()) || identity.knownTransactionId()!=null && !identity.knownTransactionId().equals(matched.transactionId()))throw rejected();
        PaymentRecord p=locked(identity.paymentRecordId());
        if(!verifiedOriginal(p) || !Objects.equals(p.getOrderId(),identity.orderId()) || p.getAmount().compareTo(identity.amountYuan())!=0)throw rejected();
        Instant time=matched.successTime();
        if(time==null || matched.payerTotalCents()==null || time.isAfter(now()))return false;
        String binding=ScopedDisputeService.digest(identity.channelBindingReference()),transaction=ScopedDisputeService.digest(matched.transactionId());
        String digest=digest(p.getId(),p.getCustomerId(),p.getStationId(),p.getOrderId(),identity.amountYuan(),binding,transaction,time.getEpochSecond(),time.getNano(),matched.payerTotalCents());
        var old=mapper.success(p.getId());
        if(old!=null){if(!digest.equals(old.get("request_digest")))throw rejected();return true;}
        var row=new HashMap<String,Object>();row.put("payment",p.getId());row.put("customer",p.getCustomerId());row.put("station",p.getStationId());row.put("order",p.getOrderId());row.put("amount",identity.amountYuan());row.put("payer",matched.payerTotalCents());row.put("binding",binding);row.put("transaction",transaction);row.put("digest",digest);row.put("epoch",time.getEpochSecond());row.put("nano",time.getNano());
        try {if(mapper.insertSuccess(row)!=1)throw rejected();}
        catch(DuplicateKeyException conflict){throw rejected();}
        return true;
    }

    /** 提交真实渠道前须先持久提交此原请求；当前没有渠道执行器或可清除未知结果的入口。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public RefundRecoveryProtocol.Request reserveOriginalRefund(RefundRecoveryProtocol.Request request) {
        if(request==null)throw rejected();PaymentRecord p=locked(request.paymentRecordId());
        if(!verifiedOriginal(p) || p.getAmount().compareTo(BigDecimal.valueOf(request.originalAmountCents(),2))!=0)throw rejected();
        String digest=digest(request.paymentRecordId(),request.channelBindingReference(),request.originalTransactionReference(),request.requestKey(),request.originalAmountCents(),request.refundAmountCents(),request.currency());
        var old=mapper.hold(p.getId(),request.requestKey());
        if(old!=null){if(!digest.equals(old.get("request_digest")))throw rejected();return request(old);}
        var proof=mapper.success(p.getId());
        if(!sameOriginal(proof,p) || !ScopedDisputeService.digest(request.channelBindingReference()).equals(proof.get("binding_digest")) || !ScopedDisputeService.digest(request.originalTransactionReference()).equals(proof.get("transaction_digest")))throw rejected();
        var decision=window(p,proof,now(),false);
        BigDecimal alreadyHeld=mapper.heldAmount(p.getId());
        if(decision.route()!=DepositRefundWindow.Route.ORIGINAL_CHANNEL || decision.remaining()==null || BigDecimal.valueOf(request.refundAmountCents(),2).compareTo(decision.remaining().subtract(alreadyHeld))>0)throw new BusinessException("原渠道时间或剩余可退金额不满足，保留原责任，不创建新退款请求");
        var row=new HashMap<String,Object>();row.put("payment",p.getId());row.put("key",request.requestKey());row.put("reference",request.channelBindingReference());row.put("binding",proof.get("binding_digest"));row.put("transaction",request.originalTransactionReference());row.put("original",BigDecimal.valueOf(request.originalAmountCents(),2));row.put("amount",BigDecimal.valueOf(request.refundAmountCents(),2));row.put("digest",digest);
        try {if(mapper.insertHold(row)!=1)throw rejected();}
        catch(DuplicateKeyException conflict){throw rejected();}
        return request;
    }
    public Optional<RefundRecoveryProtocol.QueryPlan> queryOriginal(Long payment,String key) {
        var old=mapper.hold(payment,key);
        return old==null?Optional.empty():RefundRecoveryProtocol.queryPlan(RefundRecoveryProtocol.awaitingResult(request(old)));
    }

    public Routing route(PaymentRecord p,Long assetStation){return routeAt(p,assetStation,now());}
    public Routing routeAt(PaymentRecord p,Long assetStation,Instant when) {
        if(p==null || p.getId()==null || p.getPaymentMethod()==null || p.getAmount()==null || p.getAmount().signum()<=0 || p.getStatus()==null || !Set.of(PaymentStatus.PAID,PaymentStatus.REFUNDED).contains(p.getStatus()))return pending("原收款状态或金额待核实",null);
        BigDecimal spent=mapper.refunded(p.getId()),remaining=p.getAmount().subtract(spent);
        if(spent.signum()<0 || remaining.signum()<0 || remaining.signum()>0 && p.getStatus()==PaymentStatus.REFUNDED)return pending("原实退凭据与原款余额待核实",null);
        if(mapper.holds(p.getId())>0)return pending("原渠道结果仍待核实，不能再次线下退款",remaining);
        if(remaining.signum()==0)return pending("原款已无可退余额，请核对原实退记录",remaining);
        if(p.getPaymentMethod()==PayMethod.CASH)return new Routing("CASH",true,remaining,"沿原现金与剩余实退金额办理",false);
        if(p.getPaymentMethod()!=PayMethod.WECHAT)return pending("原收款方式待核实",remaining);
        var proof=mapper.success(p.getId());var decision=window(p,proof,when,false);
        if(!Objects.equals(p.getStationId(),assetStation))return pending("原微信收款责任站与资产归属待核实，不改交现金",remaining);
        if(decision.route()==DepositRefundWindow.Route.OFFLINE_HANDOVER)return new Routing("CASH",true,decision.remaining(),decision.reason(),true);
        return new Routing("ONLINE",false,decision.remaining(),decision.reason(),false);
    }
    private DepositRefundWindow.Decision window(PaymentRecord p,Map<String,Object> proof,Instant now,boolean uncertain) {
        Instant time=null;boolean same=sameOriginal(proof,p),actual=false;
        if(same)try {time=Instant.ofEpochSecond(number(proof,"success_epoch_second"),number(proof,"success_nano"));actual=number(proof,"payer_total_cents")==p.getAmount().movePointRight(2).longValueExact();}
        catch(RuntimeException bad){same=false;}
        return DepositRefundWindow.decide(new DepositRefundWindow.Facts(time,p.getAmount(),mapper.refunded(p.getId()),same,actual,uncertain),now);
    }
    private static Routing pending(String reason,BigDecimal remaining){return new Routing("PENDING",false,remaining,reason,false);}
    private PaymentRecord locked(Long id) {
        PaymentRecord observed=payments.getById(id);if(observed==null)throw rejected();
        if(observed.getOrderId()!=null)locks.lockOrder(observed.getOrderId());return locks.lockPayment(id);
    }
    private static boolean verifiedOriginal(PaymentRecord p) {
        return p!=null && p.getPaymentMethod()!=null && p.getPaymentMethod()==PayMethod.WECHAT && p.getStatus()!=null && Set.of(PaymentStatus.PAID,PaymentStatus.REFUNDED).contains(p.getStatus()) && p.getAmount()!=null && p.getAmount().signum()>0 && p.getCustomerId()!=null && p.getStationId()!=null;
    }
    private static boolean sameOriginal(Map<String,Object> row,PaymentRecord p) {
        return row!=null && Objects.equals(number(row,"payment_id"),p.getId()) && Objects.equals(number(row,"customer_id"),p.getCustomerId()) && Objects.equals(number(row,"station_id"),p.getStationId()) && Objects.equals(row.get("order_id")==null?null:number(row,"order_id"),p.getOrderId()) && decimal(row.get("amount")).compareTo(p.getAmount())==0;
    }
    private static RefundRecoveryProtocol.Request request(Map<String,Object> row) {return new RefundRecoveryProtocol.Request(number(row,"payment_id"),String.valueOf(row.get("binding_reference")),String.valueOf(row.get("original_transaction_reference")),String.valueOf(row.get("request_key")),decimal(row.get("original_amount")).movePointRight(2).longValueExact(),decimal(row.get("amount")).movePointRight(2).longValueExact(),"CNY");}
    private static Long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static BigDecimal decimal(Object value){return value instanceof BigDecimal money?money:new BigDecimal(String.valueOf(value));}
    private static String digest(Object... values) {var text=new StringBuilder();for(Object value:values){String part=String.valueOf(value);text.append(part.length()).append(':').append(part);}return ScopedDisputeService.digest(text.toString());}
    private static BusinessException rejected(){return new BusinessException("原支付时间或原请求凭据不一致，请保留原记录核实");}
}
