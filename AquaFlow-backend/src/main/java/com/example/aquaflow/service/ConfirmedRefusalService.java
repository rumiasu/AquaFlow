package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 确认拒付只影响信用，不撤销已经付清的桶权益，也不消灭应收。 */
@Service
@RequiredArgsConstructor
public class ConfirmedRefusalService {
    private final ConfirmedRefusalMapper mapper;
    private final CustomerStationConfigMapper configs;
    private final AlertService alerts;
    private final BarrelBusinessPolicy policy;

    @Transactional
    public void record(Orders order,Long exception,String note) {
        if (!Integer.valueOf(1).equals(order.getPaymentStatus())) throw new BusinessException("只有待收款订单可确认拒付");
        Long debt=StationUtil.settleStation(order), asset=order.getStationId();
        if (!Objects.equals(debt,AuthContext.requireStationId())) throw new BusinessException("拒付须由本单结算站确认");
        if (mapper.insert(order.getId(),exception,order.getCustomerId(),asset,debt,Objects.equals(asset,debt),AuthContext.getUserId(),note)!=1)
            throw new BusinessException("拒付记录未保存");
        configs.ensureExists(order.getCustomerId(),debt);
        configs.updateOfflinePaymentEnabled(order.getCustomerId(),debt,0);
        alerts.stationFault(debt,"WARN","ConfirmedRefusal","已确认客户拒付", "订单 "+order.getId()+" 欠款仍须追收，已关闭线下付款；未扣押金。","ORDER",order.getId());
        if (!Objects.equals(asset,debt)) alerts.stationFault(asset,"WARN","ConfirmedRefusal","他站确认拒付待核实",
                "订单 "+order.getId()+" 的履约站确认拒付；请核实后决定是否冻结本站退押金资格。","ORDER",order.getId());
    }

    /** 仍可建单；支付新单前结清相关站的已确认拒付，旧欠款本身允许补收。 */
    @Transactional
    public void requireOldDebtPaid(Orders order) {
        if (!policy.hasSchema()) return;
        for (Map<String,Object> r:mapper.activeForUpdate(order.getCustomerId(),order.getStationId())) {
            if (((Number)r.get("order_id")).longValue()!=order.getId())
                throw new BusinessException("请先补付已确认拒付的欠款（订单 "+r.get("order_id")+"），再支付本次订单");
        }
    }

    public boolean frozen(Long customer,Long station) { return policy.hasSchema() && mapper.frozen(customer,station)>0; }
    public Set<Long> frozenCustomers(Long station) {
        Set<Long> result=new HashSet<>();
        if (policy.hasSchema()) for (Map<String,Object> r:mapper.frozenCustomers(station)) result.add(((Number)r.get("customerId")).longValue());
        return result;
    }
    public List<Map<String,Object>> list(Long station) { return policy.hasSchema()?mapper.list(station):List.of(); }
    @Transactional
    public void confirmFreeze(Long order,Long station) {
        if (mapper.confirmFreeze(order,station,AuthContext.getUserId())!=1) throw new BusinessException("无权确认或已经确认该冻结，请刷新台账");
    }
}
