package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 消费退款按组成退回，资产退款仍走退桶；补偿不能伪装成无限额正常退款。 */
@Service
public class ConsumptionRefundService {
    @Autowired private OrderBarrelPurchaseService orderPurchases;
    @Autowired private ConsumptionRefundMapper receipts;
    @Autowired private PaymentRecordMapper payments;
    @Autowired private TicketPaymentService ticketPayments;
    @Autowired private OrderMapper orders;
    @Autowired private UnusedTicketRefundService unusedTickets;
    @Autowired private ApprovedBarrelReturnService approvedReturns;
    @Autowired private BarrelLedgerService barrels;
    @Autowired private IndependentBarrelService independent;
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private StationRecoveryService recoveries;
    @Autowired @Lazy private PaymentService paymentService;
    @Autowired private com.example.aquaflow.payment.MockPaymentPolicy mockPolicy;
    public boolean usesIndependentRules(Long paymentId) {
        PaymentRecord check=payments.getById(paymentId);
        // 水票退出是 v36 批次账能力，不应被桶权益开关顺带关掉；只要 v71 旁表已安装就走批次退出。
        if(check!=null && check.getOrderId()==null && check.getTicketQty()!=null && check.getTicketQty()>0)
            return policy.hasSchema();
        if(check!=null && check.getOrderId()!=null && ticketPayments.get(check.getOrderId())!=null)return true;
        if(policy.isEnabled())return true;
        if(!policy.hasSchema())return false;
        PaymentRecord p=payments.getById(paymentId);
        return p!=null && (p.getOrderId()!=null?barrels.independentOrder(p.getOrderId()):independent.isPurchasePayment(paymentId) || approvedReturns.isFeePayment(paymentId));
    }

    @Transactional
    public void refund(Long paymentId,String requestedScope,String note,Integer expectedTicketQty,BigDecimal expectedTicketAmount,BigDecimal expectedRefundAmount,String idempotencyKey) {
        PaymentRecord original=payments.getById(paymentId);
        if (original==null) throw new BusinessException("支付记录不存在");
        String scope=requestedScope==null?"WATER":requestedScope.toUpperCase(Locale.ROOT);
        if (!Set.of("WATER","SERVICE","ALL_CONSUMPTION").contains(scope)) throw new BusinessException("请选择水费、配送费用或全部消费费用");
        if (original.getOrderId()==null) {
            if (original.getTicketQty()!=null && original.getTicketQty()>0) unusedTickets.refund(paymentId,note,expectedTicketQty,expectedTicketAmount,idempotencyKey);
            else if(!approvedReturns.refundFee(paymentId,note)) paymentService.refundPayment(paymentId,note);
            return;
        }
        Orders order=receipts.lockOrder(original.getOrderId());
        if (order==null || !Objects.equals(StationUtil.settleStation(order),AuthContext.requireStationId())) throw new BusinessException("仅本单结算站可退消费费用");
        original=receipts.lockPayment(paymentId);
        if(ticketPayments.get(order.getId())!=null) { refundSplit(order,original,scope,note,expectedTicketQty,expectedRefundAmount);return; }
        if (!Integer.valueOf(PaymentStatus.PAID).equals(original.getStatus())) throw new BusinessException("该笔消费已退款或尚未实际收款");
        boolean combined=orderPurchases.hasPurchase(order.getId());
        if(combined && expectedRefundAmount==null)
            throw new BusinessException("请重新预览并明确确认退款金额；旧请求不能静默退款");
        if (OrderStatus.isCancellable(order.getStatus())) {
            if(combined)paymentService.refundOrder(order.getId(),note,expectedRefundAmount);
            else paymentService.refundOrder(order.getId(),note);
            return;
        }
        if (original.getPaymentMethod()==PayMethod.TICKET) {
            if ("SERVICE".equals(scope)) throw new BusinessException("水票支付只能整笔回补水票，请选择退水费并确认整笔回补");
            if (nz(original.getBarrelDeposit()).signum()>0) throw new BusinessException("历史票款包含押金，请由站长拆分核实资产后处理");
            paymentService.refundPayment(paymentId,note); return;
        }
        mockPolicy.requireRefund(original);
        Map<String,Object> prior=orderPurchases.currentRefundTotals(paymentId);
        BigDecimal water=nz(original.getWaterAmount()).subtract(decimal(prior.get("waterAmount")));
        BigDecimal delivery=nz(order.getDeliveryFee()).subtract(decimal(prior.get("deliveryFee")));
        BigDecimal floor=nz(order.getFloorFee()).subtract(decimal(prior.get("floorFee")));
        if ("WATER".equals(scope)) { delivery=BigDecimal.ZERO; floor=BigDecimal.ZERO; }
        if ("SERVICE".equals(scope)) water=BigDecimal.ZERO;
        BigDecimal amount=water.add(delivery).add(floor);
        if (water.signum()<0 || delivery.signum()<0 || floor.signum()<0 || amount.signum()<=0)
            throw new BusinessException("所选费用已经退完，不能重复退款");
        BigDecimal priorTotal=decimal(prior.get("waterAmount")).add(decimal(prior.get("deliveryFee"))).add(decimal(prior.get("floorFee")));
        if(priorTotal.add(amount).compareTo(nz(original.getAmount()).subtract(nz(original.getBarrelDeposit())))>0)
            throw new BusinessException("消费金额与原收款记录不一致，请站长核实，不能超额退款");
        if(expectedRefundAmount!=null && expectedRefundAmount.compareTo(amount)!=0)
            throw new BusinessException("退款金额已变化，请重新预览并确认实际交付金额；本次未登记退款");
        if(combined)orderPurchases.assertRefundRoom(original,amount);
        PaymentRecord refund=new PaymentRecord();
        refund.setOrderId(order.getId()); refund.setCustomerId(original.getCustomerId());
        // 现金由获授权的当前结算站实际交付；微信仍认原商户原渠道。原款关联保留在退款凭据中。
        refund.setStationId(original.getPaymentMethod()==PayMethod.CASH?AuthContext.requireStationId():original.getStationId()); refund.setPaymentMethod(original.getPaymentMethod());
        refund.setAmount(amount.negate()); refund.setWaterAmount(water.negate()); refund.setBarrelDeposit(BigDecimal.ZERO);
        refund.setDeliveryFee(delivery.negate()); refund.setFloorFee(floor.negate()); refund.setStatus(PaymentStatus.REFUNDED);
        refund.setOperatorId(AuthContext.getUserId()); String memo="消费退款 "+scope+"；"+(note==null?"":note);
        refund.setNote(memo.substring(0,Math.min(200,memo.length()))); refund.setCreateTime(LocalDateTime.now()); refund.setUpdateTime(LocalDateTime.now());
        mockPolicy.markRefund(original,refund);
        payments.insert(refund);
        if (receipts.record(paymentId,refund.getId(),order.getId(),scope,water,delivery,floor,AuthContext.getUserId(),note)!=1)
            throw new BusinessException("退款记录未保存，本次登记未生效");
        BigDecimal refundedTotal=decimal(prior.get("waterAmount")).add(decimal(prior.get("deliveryFee"))).add(decimal(prior.get("floorFee"))).add(amount);
        if(orderPurchases.hasPurchase(order.getId())) {
            orderPurchases.finishPayment(original);
            if(refundedTotal.compareTo(nz(original.getAmount()).subtract(nz(original.getBarrelDeposit())))==0)
                orders.updatePaymentStatusIf(order.getId(),PaymentStatus.PAID,PaymentStatus.REFUNDED);
        } else if (refundedTotal.compareTo(nz(original.getAmount()))==0) {
            if (payments.updateStatusTo(paymentId,PaymentStatus.REFUNDED,PaymentStatus.PAID)!=1
                    || orders.updatePaymentStatusIf(order.getId(),PaymentStatus.PAID,PaymentStatus.REFUNDED)!=1)
                throw new BusinessException("支付状态已变化，本次退款登记未生效");
        }
        orders.appendSpecialNote(order.getId(),"[消费退款] "+scope+" "+amount+" 元；桶权益及押金未变；"+(note==null?"":note));
        recoveries.preserveFullRefund(order.getId());
    }
    public List<Map<String,Object>> records(Long order) { return receipts.records(order); }
    public Map<String,Object> preview(Long paymentId) {
        PaymentRecord p=payments.getById(paymentId);
        if(p==null)throw new BusinessException("支付记录不存在");
        if(p.getOrderId()==null && p.getTicketQty()!=null && p.getTicketQty()>0) {
            Map<String,Object> batch=unusedTickets.preview(paymentId);
            return Map.of("notice","按原购买批次退剩余票；现金必须实际交付，数量或金额变化须重新确认","expectedTicketQty",batch.get("remainingQty"),"expectedTicketAmount",batch.get("refundAmount"),"scopes",List.of(Map.of("scope","WATER","label","退剩余 "+batch.get("remainingQty")+" 张票 ¥"+batch.get("refundAmount"))));
        }
        if(p.getOrderId()==null)return Map.of("notice","资产押金须走退桶申请，服务款退款须核实原款", "scopes",List.of(Map.of("scope","SERVICE","label","核实原服务款退款")));
        Orders order=orders.getById(p.getOrderId());
        // 取消预览也不得承诺当前不能执行的微信退款；提交时仍以锁定的原款复核。
        if (OrderStatus.isCancellable(order.getStatus()) && Objects.equals(p.getStatus(), PaymentStatus.PAID)
                && Objects.equals(p.getPaymentMethod(), PayMethod.WECHAT) && !mockPolicy.canRefund(p))
            return Map.of("notice", "微信原款尚无法自动退款，本单暂不能取消，请联系水站核实处理", "scopes", List.of());
        if(ticketPayments.get(order.getId())!=null)return previewSplit(order,p);
        Map<String,Object> prior=receipts.refunded(paymentId);
        if(p.getPaymentMethod()==PayMethod.TICKET)return Map.of("notice","水票整笔回补，不折现金，不撤桶权益", "scopes",List.of(Map.of("scope","WATER","label","整笔回补水票")));
        if(OrderStatus.isCancellable(order.getStatus())) {
            if(orderPurchases.hasPurchase(order.getId()))return orderPurchases.cancelPreview(order,p);
            return Map.of("notice","尚未交付的订单走整单取消，释放库存和权益占用；已独立购买的押金仍保留", "scopes",List.of(Map.of("scope","ALL_CONSUMPTION","label","取消订单并退消费款 ¥"+p.getAmount())));
        }
        BigDecimal water=nz(p.getWaterAmount()).subtract(decimal(prior.get("waterAmount")));
        BigDecimal fee=nz(order.getDeliveryFee()).add(nz(order.getFloorFee())).subtract(decimal(prior.get("deliveryFee"))).subtract(decimal(prior.get("floorFee")));
        List<Map<String,Object>> choices=new ArrayList<>();
        if(water.signum()>0)choices.add(Map.of("scope","WATER","label","仅退水费 ¥"+water,"expectedRefundAmount",water,"confirmationRequired",orderPurchases.hasPurchase(order.getId())));
        if(fee.signum()>0)choices.add(Map.of("scope","SERVICE","label","退配送与楼层费 ¥"+fee,"expectedRefundAmount",fee,"confirmationRequired",orderPurchases.hasPurchase(order.getId())));
        if(water.signum()>0 && fee.signum()>0)choices.add(Map.of("scope","ALL_CONSUMPTION","label","退全部剩余消费款 ¥"+water.add(fee),"expectedRefundAmount",water.add(fee),"confirmationRequired",orderPurchases.hasPurchase(order.getId())));
        return Map.of("notice","不撤桶权益、不退押金；现金须核实实际交付金额，金额变化须重新预览确认；微信须原渠道成功退款后才可登记", "scopes",choices);
    }
    private Map<String,Object> currentSplitRefunds(Long payment) {
        BigDecimal delivery=BigDecimal.ZERO,floor=BigDecimal.ZERO;
        for(var row:receipts.refundRowsForUpdate(payment)) { delivery=delivery.add(decimal(row.get("deliveryFee")));floor=floor.add(decimal(row.get("floorFee"))); }
        return Map.of("deliveryFee",delivery,"floorFee",floor);
    }
    private Map<String,Object> previewSplit(Orders order,PaymentRecord original) {
        var split=ticketPayments.get(order.getId());
        BigDecimal refunded=decimal(receipts.refunded(original.getId()).get("deliveryFee")).add(decimal(receipts.refunded(original.getId()).get("floorFee")));
        BigDecimal cash=original.getStatus()==PaymentStatus.PAID?split.cashDue().subtract(split.deposit()).subtract(refunded):BigDecimal.ZERO;
        int qty=ticketPayments.refundableQty(order.getId());List<Map<String,Object>> scopes=new ArrayList<>();
        boolean cancel=OrderStatus.isCancellable(order.getStatus());
        if(cancel)scopes.add(Map.of("scope","ALL_CONSUMPTION","label","取消原单：释放或回补水票，实际退服务费 ¥"+cash,"expectedRefundAmount",cash,"expectedTicketQty",qty,"confirmationRequired",true));
        else {
            if(qty>0)scopes.add(Map.of("scope","WATER","label","按原批次回补 "+qty+" 张水票，不退水费现金","expectedRefundAmount",BigDecimal.ZERO,"expectedTicketQty",qty,"confirmationRequired",true));
            if(cash.signum()>0)scopes.add(Map.of("scope","SERVICE","label","实际退服务费 ¥"+cash,"expectedRefundAmount",cash,"expectedTicketQty",0,"confirmationRequired",true));
            if(qty>0 && cash.signum()>0)scopes.add(Map.of("scope","ALL_CONSUMPTION","label","回补 "+qty+" 张水票，实际退服务费 ¥"+cash,"expectedRefundAmount",cash,"expectedTicketQty",qty,"confirmationRequired",true));
        }
        return Map.of("notice","水票按原批次退回，不折现金；仅退实际收取的服务费。已用配送优惠不折现金、不恢复。现金退款须已实际交付。桶押金仍走退桶。","scopes",scopes);
    }
    private void refundSplit(Orders order,PaymentRecord original,String scope,String note,Integer expectedQty,BigDecimal expectedCash) {
        if(note==null || note.isBlank())throw new BusinessException("请填写本次退款核对原因");
        if(OrderStatus.isCancellable(order.getStatus())) { paymentService.refundOrder(order.getId(),note,expectedCash);return; }
        if(order.getStatus()!=OrderStatus.DELIVERED && order.getStatus()!=OrderStatus.COMPLETED)throw new BusinessException("请核对原订单履约状态");
        if(original.getStatus()!=PaymentStatus.PAID && original.getStatus()!=PaymentStatus.PENDING)throw new BusinessException("原款已终止，不能重复退款");
        var split=ticketPayments.getForUpdate(order.getId());boolean water=!"SERVICE".equals(scope),service=!"WATER".equals(scope);
        int qty=water?ticketPayments.refundableQty(order.getId()):0;
        var prior=currentSplitRefunds(original.getId());
        BigDecimal delivery=service && original.getStatus()==PaymentStatus.PAID?split.delivery().subtract(split.credit()).subtract(decimal(prior.get("deliveryFee"))):BigDecimal.ZERO;
        BigDecimal floor=service && original.getStatus()==PaymentStatus.PAID?split.floor().subtract(decimal(prior.get("floorFee"))):BigDecimal.ZERO;
        BigDecimal cash=delivery.add(floor);
        if(qty==0 && cash.signum()==0)throw new BusinessException("所选水票或服务费已退完，不能重复退款");
        if(expectedQty==null || expectedQty!=qty || expectedCash==null || expectedCash.compareTo(cash)!=0)
            throw new BusinessException("退款水票数量或实际金额已变化，请重新预览并确认；本次未退");
        if(cash.signum()>0) {
            mockPolicy.requireRefund(original);
            PaymentRecord refund=new PaymentRecord();refund.setOrderId(order.getId());refund.setCustomerId(order.getCustomerId());
            refund.setStationId(original.getPaymentMethod()==PayMethod.CASH?AuthContext.requireStationId():original.getStationId());refund.setPaymentMethod(original.getPaymentMethod());
            refund.setAmount(cash.negate());refund.setWaterAmount(BigDecimal.ZERO);refund.setBarrelDeposit(BigDecimal.ZERO);refund.setDeliveryFee(delivery.negate());refund.setFloorFee(floor.negate());
            refund.setStatus(PaymentStatus.REFUNDED);refund.setOperatorId(AuthContext.getUserId());String memo="水票单实际服务费退款；"+note;refund.setNote(memo.substring(0,Math.min(200,memo.length())));refund.setCreateTime(LocalDateTime.now());refund.setUpdateTime(LocalDateTime.now());
            mockPolicy.markRefund(original,refund);payments.insert(refund);
            if(receipts.record(original.getId(),refund.getId(),order.getId(),scope,BigDecimal.ZERO,delivery,floor,AuthContext.getUserId(),note)!=1)throw new BusinessException("服务费退款凭据未保存");
        }
        if(qty>0)ticketPayments.refundWater(order);
        boolean noWater=ticketPayments.refundableQty(order.getId())==0;
        BigDecimal totalRefunded=decimal(prior.get("deliveryFee")).add(decimal(prior.get("floorFee"))).add(cash);
        if(noWater && totalRefunded.compareTo(split.cashDue())==0) {
            payments.updateStatusTo(original.getId(),PaymentStatus.REFUNDED,PaymentStatus.PAID);
            orders.updatePaymentStatusIf(order.getId(),PaymentStatus.PAID,PaymentStatus.REFUNDED);
        }
        orders.appendSpecialNote(order.getId(),"[分项消费退款] 回补 "+qty+" 张水票；实际服务费 "+cash+" 元；"+note);
        recoveries.preserveFullRefund(order.getId());
    }
    private static BigDecimal nz(BigDecimal n) { return n==null?BigDecimal.ZERO:n; }
    private static BigDecimal decimal(Object n) { return n==null?BigDecimal.ZERO:new BigDecimal(n.toString()); }
}
