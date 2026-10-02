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
        InterStationSettlement current=mapper.lockSettlement(row.getOrderId());
        if (current!=null && Integer.valueOf(2).equals(current.getStatus()) && current.getAmount().signum()>0) {
            java.math.BigDecimal amount=current.getAmount().subtract(mapper.refundedByReceiver(row.getOrderId(),current.getToStationId())).max(java.math.BigDecimal.ZERO);
            // 履约站已经实际替客户退的现金不可再追回第二次；保留原款和退款凭据供双方核实。
            if(amount.signum()==0)return;
            if (mapper.insert(row.getOrderId(),current.getToStationId(),current.getFromStationId(),amount,"原站间款已交付；减去原收款方已实际退给客户的现金后待返还，不代表资金已追回")!=1)
                throw new BusinessException("已付款追回凭据未建立，本次冲销回滚");
        }
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
