package com.example.aquaflow.service;
import com.example.aquaflow.entity.InterStationSettlement;
import com.example.aquaflow.mapper.StationRecoveryMapper;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StationRecoveryService {
    private final StationRecoveryMapper mapper;
    private final BarrelBusinessPolicy policy;
    private final com.example.aquaflow.mapper.DispatchAgreementMapper agreements;
    private final com.example.aquaflow.mapper.ConsumptionRefundMapper locks;
    @Transactional
    public void preserveSettledPayment(InterStationSettlement row) {
        if (!policy.isEnabled() && (!policy.hasSchema() || agreements.get(row.getOrderId())==null)) return;
        com.example.aquaflow.entity.Orders order=locks.lockOrder(row.getOrderId());
        if(order==null || order.getStatus()!=5 && order.getPaymentStatus()!=3)
            throw new BusinessException("已结清的有效订单不能单方冲销；须先取消或完成消费退款，再核实返还责任");
        preserveTerminalRefund(order);
    }

    /** 全退/取消的资金事实留债务；不替部分售后判断责任，也不表示站间钱已交付。 */
    @Transactional
    public void preserveFullRefund(Long orderId) {
        if (!policy.hasSchema()) return;
        if (!policy.isEnabled() && agreements.get(orderId)==null) return;
        com.example.aquaflow.entity.Orders order=locks.lockOrder(orderId);
        if (order==null || order.getStatus()!=5 && order.getPaymentStatus()!=3) return;
        preserveTerminalRefund(order);
    }

    private void preserveTerminalRefund(com.example.aquaflow.entity.Orders order) {
        Long id=order.getId();InterStationSettlement settled=mapper.lockSettlement(id);
        Long originalCashStation=mapper.cashCollectionStation(id);
        Long from=settled!=null?settled.getFromStationId():(originalCashStation!=null?originalCashStation:order.getStationId());
        Long to=settled!=null?settled.getToStationId():com.example.aquaflow.util.StationUtil.settleStation(order);
        if(from==null || to==null || Objects.equals(from,to))return;
        java.math.BigDecimal transferred=settled!=null && settled.getSettledTime()!=null?settled.getAmount():java.math.BigDecimal.ZERO;
        // 2026-10-08：旧实现只处理已结款，未结时B代退会丢掉A持有原款的责任。
        // 客户消费原款净额减已交付站间款，得出唯一待返还净额；三笔事实不可重复扣减。
        // 同一请求可能在等订单锁前已有普通读快照；必须当前读，才能计入另一请求刚完成的部分退款。
        java.math.BigDecimal cashNet=java.math.BigDecimal.ZERO;
        for(com.example.aquaflow.entity.PaymentRecord receipt:mapper.cashForUpdate(id,from)) {
            if(receipt.getAmount().signum()>0 || Integer.valueOf(3).equals(receipt.getStatus()))
                cashNet=cashNet.add(receipt.getAmount().subtract(receipt.getBarrelDeposit()==null?java.math.BigDecimal.ZERO:receipt.getBarrelDeposit()));
        }
        java.math.BigDecimal net=cashNet.subtract(transferred);
        if(net.signum()==0)return;
        Long payer=net.signum()>0?from:to,receiver=net.signum()>0?to:from;
        java.math.BigDecimal amount=net.abs();Map<String,Object> existing=mapper.lockRecovery(id);
        if(existing!=null) {
            if(!Objects.equals(payer,((Number)existing.get("fromStationId")).longValue())
                    || !Objects.equals(receiver,((Number)existing.get("toStationId")).longValue())
                    || amount.compareTo(new java.math.BigDecimal(existing.get("amount").toString()))!=0)
                throw new BusinessException("返还原款与现有凭据不一致，请先核实实际交款；本次资金登记回滚");
            return;
        }
        if(mapper.insert(id,payer,receiver,amount,"客户原消费款、实际退款与已交付站间款已核对；待返还净额，尚未确认交付到账")!=1)
            throw new BusinessException("资金返还凭据未建立，本次退款或冲销回滚");
    }
    public List<Map<String,Object>> list(Long station) { return policy.hasSchema()?mapper.list(station):List.of(); }
    @Transactional public void sent(Long order,Long station,String note) {
        if (note==null || note.isBlank() || note.length()>500) throw new BusinessException("请登记返还款的交付凭据（最多500字）");
        if (mapper.sent(order,station,AuthContext.getUserId(),note)!=1) throw new BusinessException("无权登记或已登记返还款交付");
    }
    @Transactional public void received(Long order,Long station) {
        if (mapper.received(order,station,AuthContext.getUserId())!=1) throw new BusinessException("仅收款站能确认已交付的返还款实际到账");
    }
}
