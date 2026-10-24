package com.example.aquaflow.service;

import com.example.aquaflow.dto.StationAftercareDTO;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.util.*;

/** 原款实退与站间责任独立留痕；本服务不调用收退款、资产或外部转款。 */
@Service @RequiredArgsConstructor
public class StationAftercareService {
    private final StationAftercareMapper mapper;
    private final ConsumptionRefundMapper locks;
    private final StationRecoveryMapper settlements;
    private final RecordDisplayNameService displayNames;
    public boolean hasCase(Long order) {return mapper.count(order)>0;}
    public List<Long> pendingOrders(Long station) {
        AuthContext.requireManager();if(!Objects.equals(station,AuthContext.requireStationId()))throw new BusinessException("只能核对本站责任订单");
        return mapper.pendingOrders(station);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> view(Long id,Long station) {
        Orders order=locks.lockOrder(id);authorize(order,station);
        var settlement=settlements.lockSettlement(id);
        boolean settled=settlement!=null && settlement.getSettledTime()!=null;
        var sources=mapper.sources(id);BigDecimal refunded=BigDecimal.ZERO;
        for(var source:sources)refunded=refunded.add(amount(source));
        boolean knownFlow=hasCase(id),partial=refunded.compareTo(mapper.originalConsumption(id))<0;
        boolean eligible=settled && mapper.legacyRecovery(id)==0 && refunded.compareTo(mapper.originalConsumption(id))<=0
                && sources.stream().allMatch(source->proof(source,order)) && (partial || knownFlow);
        var items=new ArrayList<Map<String,Object>>();
        for(var source:sources) {
            var item=new LinkedHashMap<String,Object>();Long payment=number(source,"refund_payment_id");var row=mapper.get(payment);
            boolean proof=proof(source,order);Long refundStation=number(source,"refundStationId");
            Long other=Objects.equals(refundStation,order.getStationId())?StationUtil.settleStation(order):order.getStationId();
            if(row!=null && (!Objects.equals(number(row,"refund_station_id"),refundStation) || !Objects.equals(number(row,"other_station_id"),other) || decimal(row.get("refund_amount")).compareTo(amount(source))!=0))proof=false;
            item.put("refundVerified",proof);
            item.put("refundPaymentId",payment);item.put("refundAmount",amount(source));item.put("refundStationId",refundStation);item.put("otherStationId",other);
            item.put("version",row==null?0:number(row,"version"));item.put("amount",row==null?null:row.get("proposed_amount"));item.put("reason",row==null?"":row.get("reason"));
            String state=row==null?"UNRESOLVED":String.valueOf(row.get("status"));item.put("status",state);
            item.put("statusText",!proof?"原款证据待核":switch(state){case "PROPOSED"->"责任方案待对方核对";case "AGREED"->decimal(row.get("proposed_amount")).signum()==0?"分担已核定，无站间补款":"分担已核定，待实际交付";case "PAID"->"已登记实际交付";default->"责任金额待协商";});
            item.put("canPropose",eligible && proof && (row==null || "PROPOSED".equals(state)));
            item.put("canAgree",eligible && proof && row!=null && "PROPOSED".equals(state) && !Objects.equals(number(row,"proposed_station_id"),station));
            item.put("canPay",eligible && proof && row!=null && "AGREED".equals(state) && decimal(row.get("proposed_amount")).signum()>0 && Objects.equals(other,station));
            item.put("history",mapper.history(payment));items.add(item);
        }
        displayNames.stations(items, "refundStation", "otherStation");
        return Map.of("orderId",id,"items",items,"eligible",eligible,"note",eligible?"客户退款已独立登记；按每笔实际责任额协商，不自动追回整单报酬。金额为另一站向实际退款站分担，其余由退款站承担。":"原结清或退款证据未满足部分售后条件；保留原记录，不自动分担或记已付款。");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> act(Long payment,Long station,String action,StationAftercareDTO dto) {
        AuthContext.requireManager();
        if(dto==null || dto.getExpectedVersion()==null || dto.getExpectedVersion()<0 || !Set.of("PROPOSE","AGREE","PAID").contains(action))throw new BusinessException("请核对原责任记录和版本");
        var source=mapper.source(payment);if(source==null)throw new BusinessException("原消费实退凭据不存在");
        Long orderId=number(source,"order_id");Orders order=locks.lockOrder(orderId);authorize(order,station);
        String key=ScopedDisputeService.key(dto.getIdempotencyKey());String actor="STAFF:"+AuthContext.getUserId()+":"+station;
        String reason=dto.getReason()==null?"":dto.getReason().trim();if(reason.length()>1000)throw new BusinessException("责任说明最多1000字");
        BigDecimal requested=dto.getAmount();
        if(requested!=null && (requested.signum()<0 || requested.scale()>2 || requested.compareTo(new BigDecimal("99999999.99"))>0)
                || requested==null && ("PROPOSE".equals(action) || "PAID".equals(action)))throw new BusinessException("请核对实际责任金额（可明确为零）");
        String digest=ScopedDisputeService.digest(action+"|"+dto.getExpectedVersion()+"|"+(requested==null?"":requested.setScale(2))+"|"+reason);
        var old=mapper.action(payment,actor,key);
        if(old!=null){if(!digest.equals(old.get("request_digest")))throw new BusinessException("同一办理编号不能改变责任内容");return receipt(old,payment,orderId);}
        source=mapper.source(payment);var settlement=settlements.lockSettlement(orderId);var current=mapper.lock(payment);
        if(!proof(source,order) || settlement==null || settlement.getSettledTime()==null || mapper.legacyRecovery(orderId)>0)throw new BusinessException("原实退或结清证据待核，不能登记责任或交付");
        BigDecimal refunded=mapper.sources(orderId).stream().map(StationAftercareService::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(refunded.compareTo(mapper.originalConsumption(orderId))>0 || mapper.sources(orderId).stream().anyMatch(s->!proof(s,order)))throw new BusinessException("原消费退款累计或来源待核，保留责任不重复办理");
        if(!hasCase(orderId) && refunded.compareTo(mapper.originalConsumption(orderId))>=0)throw new BusinessException("整笔退款沿原清结记录核实，不以部分售后重复追收");
        long version=current==null?0:number(current,"version");if(version!=dto.getExpectedVersion())throw new BusinessException("责任方案已变化，请按原编号核对");
        Long refundStation=number(source,"refundStationId"),other=Objects.equals(refundStation,order.getStationId())?StationUtil.settleStation(order):order.getStationId();
        if(current!=null && (!Objects.equals(number(current,"refund_station_id"),refundStation) || !Objects.equals(number(current,"other_station_id"),other)
                || decimal(current.get("refund_amount")).compareTo(amount(source))!=0))throw new BusinessException("原责任与实退证据不一致，请保留原记录核实");
        var row=new LinkedHashMap<String,Object>();row.put("payment",payment);row.put("order",orderId);row.put("refundStation",refundStation);row.put("otherStation",other);row.put("refundAmount",amount(source));row.put("station",station);row.put("operator",AuthContext.getUserId());row.put("reason",reason);row.put("version",version);
        BigDecimal assigned=current==null?BigDecimal.ZERO:decimal(current.get("proposed_amount"));int changed;
        if("PROPOSE".equals(action)) {
            if(reason.isBlank() || requested.compareTo(amount(source))>0)throw new BusinessException("责任方案须说明事实，且分担不超过本笔实际消费退款");
            if(current!=null && !"PROPOSED".equals(current.get("status")))throw new BusinessException("已核定责任不能覆盖，须保留原事实另行核实");
            assigned=requested.setScale(2);row.put("amount",assigned);changed=current==null?mapper.insert(row):mapper.propose(row);
        } else {
            if(current==null)throw new BusinessException("请先核对原责任方案");row.put("amount",assigned);
            if("AGREE".equals(action))changed=mapper.agree(row);
            else {
                if(assigned.signum()<=0 || requested.compareTo(assigned)!=0)throw new BusinessException("实际交付金额须与双方核定责任一致，不得重复或超额");
                changed=mapper.paid(row);
            }
        }
        if(changed!=1)throw new BusinessException("无权办理或状态已变化，请核对原责任结果");
        row.put("actor",actor);row.put("key",key);row.put("digest",digest);row.put("action",action);row.put("amount",assigned);
        if(mapper.append(row)!=1)throw new BusinessException("责任审计未保存，本次整体回滚");return receipt(row,payment,orderId);
    }
    private void authorize(Orders order,Long station) {
        AuthContext.requireManager();
        if(order==null || !Objects.equals(station,AuthContext.requireStationId()) || Objects.equals(order.getStationId(),StationUtil.settleStation(order)) || !Set.of(order.getStationId(),StationUtil.settleStation(order)).contains(station))throw new BusinessException("仅本单两站站长可核对售后分担");
    }
    private static boolean proof(Map<String,Object> row,Orders order) {
        if(row==null || amount(row).signum()<=0 || number(row,"actualStatus")!=3 || number(row,"originalStatus")<2 || number(row,"originalStatus")>3)return false;
        if(!Set.of(1L,2L).contains(number(row,"actualMethod")))return false;
        for(var component:Map.of("water_amount","actualWater","delivery_fee","actualDelivery","floor_fee","actualFloor").entrySet())
            if(decimal(row.get(component.getKey())).signum()<0 || decimal(row.get(component.getKey())).compareTo(decimal(row.get(component.getValue())).negate())!=0)return false;
        Long station=number(row,"refundStationId");
        return Set.of(order.getStationId(),StationUtil.settleStation(order)).contains(station) && Objects.equals(number(row,"actualCustomerId"),order.getCustomerId()) && Objects.equals(number(row,"originalCustomerId"),order.getCustomerId())
                && Objects.equals(number(row,"actualOrderId"),order.getId()) && Objects.equals(number(row,"originalOrderId"),order.getId()) && Objects.equals(number(row,"actualMethod"),number(row,"originalMethod"))
                && decimal(row.get("actualDeposit")).signum()==0 && decimal(row.get("actualAmount")).negate().compareTo(amount(row))==0 && decimal(row.get("originalAmount")).subtract(decimal(row.get("originalDeposit"))).compareTo(amount(row))>=0;
    }
    private static BigDecimal amount(Map<String,Object> row) {return decimal(row.get("water_amount")).add(decimal(row.get("delivery_fee"))).add(decimal(row.get("floor_fee")));}
    private static BigDecimal decimal(Object value) {return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    private static Long number(Map<String,Object> row,String key) {Object value=row.get(key);return value instanceof Number n?n.longValue():-1L;}
    private static Map<String,Object> receipt(Map<String,Object> row,Long payment,Long order) {return Map.of("id",row.get("id"),"refundPaymentId",payment,"orderId",order,"action",row.get("action"));}
}
