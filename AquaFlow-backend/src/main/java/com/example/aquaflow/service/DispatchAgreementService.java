package com.example.aquaflow.service;
import com.example.aquaflow.constant.*;
import com.example.aquaflow.dto.DispatchQuoteDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** 每单的外包报价和净送桶补偿约定；客户押金账户始终留在归属站。 */
@Service
@RequiredArgsConstructor
public class DispatchAgreementService {
    private final DispatchAgreementMapper mapper;
    private final ConsumptionRefundMapper locks;
    private final BarrelLedgerService barrels;
    private final BarrelBusinessPolicy policy;
    private final AuditLogService audit;
    @Transactional public void prepare(Orders order,Long target) {
        if (!policy.isEnabled()) return;
        Map<String,Object> existing=mapper.lock(order.getId());
        if (existing!=null) {
            if (!"OFFERED".equals(existing.get("status"))) throw new BusinessException("已经接受的外包协议不能改派，请走指定退回协商");
            if (mapper.route(order.getId(),target)!=1) throw new BusinessException("外包协议已变更");
            return;
        }
        int qty=0; List<String> items=new ArrayList<>();
        for (BarrelRightReservation r:barrels.orderRights(order.getId())) { qty+=r.getPickupQty(); if(r.getPickupQty()>0) items.add("商品 "+r.getProductId()+"："+r.getPickupQty()+" 个同型空桶"); }
        BigDecimal service=order.getPaymentMethod()==PayMethod.TICKET?mapper.ticketActual(order.getId()):nz(order.getWaterAmount());
        service=nz(service).add(nz(order.getDeliveryFee())).add(nz(order.getFloorFee()));
        if (mapper.insert(order.getId(),order.getStationId(),target,service,qty,"RETURN_EMPTY",BigDecimal.ZERO,String.join("；",items),AuthContext.getUserId(),"先按实收消费费用报价；净领桶由归属站向履约站补同型空桶，押金不外派")!=1) throw new BusinessException("外包协议未保存");
    }
    @Transactional public void quote(Long orderId,DispatchQuoteDTO dto) {
        if (!policy.isEnabled()) throw new BusinessException("尚未开放自定义外包报价");
        Orders order=locks.lockOrder(orderId);
        if (order==null || !AuthContext.requireStationId().equals(order.getStationId()) || order.getStatus()!=OrderStatus.PENDING
                || Objects.equals(order.getStationId(),order.getDeliveryStationId())) throw new BusinessException("只能调整尚未接单的本站外派订单报价");
        Map<String,Object> agreement=mapper.lock(orderId);
        if (agreement==null || !"OFFERED".equals(agreement.get("status"))) throw new BusinessException("该单没有可调整的外包报价");
        int qty=((Number)agreement.get("net_barrels")).intValue();
        if ((qty==0 || "RETURN_EMPTY".equals(dto.getBarrelMode())) && dto.getBarrelAmount().signum()!=0) throw new BusinessException("不产生净送桶或约定补空桶时，桶结算金额须为零");
        if (qty>0 && "SETTLE_BARREL".equals(dto.getBarrelMode()) && dto.getBarrelAmount().signum()<=0) throw new BusinessException("净送桶折款须明确双方约定的金额");
        if (mapper.quote(orderId,dto.getServiceAmount(),dto.getBarrelMode(),dto.getBarrelAmount(),AuthContext.getUserId(),dto.getNote())!=1) throw new BusinessException("对方已接单，报价不可再变更");
        audit.log("DispatchAgreement","QUOTE",String.valueOf(orderId),"服务报价 "+dto.getServiceAmount()+"；桶方案 "+dto.getBarrelMode()+" "+dto.getBarrelAmount()+"；"+dto.getNote(),null);
    }
    @Transactional public void accepted(Orders order,Long station) {
        if (!policy.hasSchema() || Objects.equals(order.getStationId(),station)) return;
        Map<String,Object> agreement=mapper.lock(order.getId());
        if(agreement==null && !policy.isEnabled())return;
        if (agreement!=null && Set.of("ACCEPTED","BARREL_CLOSED").contains(String.valueOf(agreement.get("status")))
                && Objects.equals(((Number)agreement.get("target_station_id")).longValue(),station)) return;
        if (agreement==null || mapper.accept(order.getId(),station,AuthContext.getUserId())!=1) throw new BusinessException("外包报价已变更，请刷新并确认后接单");
        audit.log("DispatchAgreement","ACCEPT",String.valueOf(order.getId()),"履约站 "+station+" 接受服务和桶交接约定",null);
    }
    public Map<String,Object> info(Long order) {
        if (!policy.hasSchema()) return Map.of();
        Map<String,Object> row=mapper.get(order); if(row==null)return Map.of();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("serviceAmount",row.get("service_amount")); out.put("netBarrels",row.get("net_barrels"));
        out.put("barrelAmount",row.get("barrel_amount")); out.put("barrelItems",row.get("barrel_items")); out.put("status",row.get("status"));
        out.put("actualNetBarrels",row.get("actual_net_barrels")); out.put("actualBarrelItems",row.get("actual_barrel_items"));
        out.put("disputed",row.get("barrel_disputed")); out.put("disputeNote",row.get("dispute_note")); out.put("resolutionNote",row.get("resolution_note"));
        out.put("canEditQuote",Objects.equals(row.get("source_station_id"),AuthContext.getStationId()) && "OFFERED".equals(row.get("status")));
        out.put("barrelMode",row.get("barrel_mode")); out.put("barrelNote","SETTLE_BARREL".equals(row.get("barrel_mode"))?"净送桶另行按约定金额结算":"归属站向履约站补同型空桶，押金留在归属站"); return out;
    }
    public boolean hasSchema() { return policy.hasSchema(); }

    public BigDecimal acceptedServiceAmount(Long order) {
        Map<String,Object> r=info(order);
        if(!Set.of("ACCEPTED","BARREL_CLOSED").contains(String.valueOf(r.get("status"))))return null;
        return new BigDecimal(String.valueOf(r.get("serviceAmount")));
    }
    @Transactional
    public Map<String,Object> authorizedInfo(Long orderId,Long station) {
        Orders order=locks.lockOrder(orderId);
        if(order==null || !(Objects.equals(order.getStationId(),station)||Objects.equals(order.getDeliveryStationId(),station)
                || order.getDeliveryStationId()==null && order.getStatus()==OrderStatus.PENDING)) throw new BusinessException("该外包协议与本站无关");
        return info(orderId);
    }
    public List<Map<String,Object>> barrelBalances(Long station) { return policy.hasSchema()?mapper.barrelBalances(station):List.of(); }
    @Transactional public void delivered(Orders order,BarrelLedgerService.DeliveryOutcome outcome) {
        if(!policy.hasSchema() || Objects.equals(order.getStationId(),StationUtil.deliveryStation(order)))return;
        Map<String,Object> row=mapper.lock(order.getId()); if(row==null)return;
        Map<Long,Integer> planned=new HashMap<>();
        for(BarrelRightReservation r:barrels.orderRights(order.getId()))planned.put(r.getProductId(),r.getPickupQty());
        int qty=0; boolean disputed=false; List<String> items=new ArrayList<>();
        for(BarrelLedgerService.DeliveryOutcome.Line line:outcome.getLines()) {
            int net=line.getDelivered()-line.getReturned(); qty+=net;
            if(net!=planned.getOrDefault(line.getProductId(),0))disputed=true;
            if(net!=0)items.add("商品 "+line.getProductId()+"：净送出 "+net+" 个；正数归属站补履约站，负数反向交桶");
        }
        if(mapper.delivered(order.getId(),qty,String.join("；",items),disputed,disputed?"实际收发与预计不同，请两站核实桶来源、数量和责任后确认方案":null)!=1)
            throw new BusinessException("外派实交桶凭据未保存，本次完成配送回滚");
    }
    @Transactional public void dispute(Long order,Long station,String note) {
        if(note==null || note.isBlank() || note.length()>500)throw new BusinessException("请填写桶来源、损坏或交接争议及凭据，最多500字");
        if(mapper.dispute(order,station,note)!=1)throw new BusinessException("只能对本次已交付且未结清的跨站桶交接提出争议");
        audit.log("DispatchAgreement","BARREL_DISPUTE",String.valueOf(order),note,null);
    }
    @Transactional public void propose(Long order,Long station,com.example.aquaflow.dto.BarrelResolutionDTO dto) {
        if("RETURN_EMPTY".equals(dto.getBarrelMode()) && dto.getBarrelAmount().signum()!=0)throw new BusinessException("补空桶方式的折款金额须为零");
        if(mapper.propose(order,station,dto.getBarrelMode(),dto.getBarrelAmount(),AuthContext.getUserId(),dto.getNote())!=1)throw new BusinessException("仅归属站可提出待双方确认的桶争议方案");
        audit.log("DispatchAgreement","BARREL_PROPOSE",String.valueOf(order),dto.getBarrelMode()+" "+dto.getBarrelAmount()+"；"+dto.getNote(),null);
    }
    @Transactional public void agree(Long order,Long station) {
        if(mapper.agree(order,station,AuthContext.getUserId())!=1)throw new BusinessException("仅履约站可同意归属站提出的桶争议方案");
        audit.log("DispatchAgreement","BARREL_AGREE",String.valueOf(order),"履约站确认争议方案，实物数量未改写，仍须确认实际交付",null);
    }
    @Transactional public void closeBarrels(Long order,Long station,String note) {
        if(note==null || note.isBlank() || note.length()>500)throw new BusinessException("请填写已收空桶或桶折款的实际交付凭据");
        if(mapper.closeBarrels(order,station,AuthContext.getUserId(),note)!=1)throw new BusinessException("仅两站当事方可确认本方实际交付；须先解决争议，不能重复确认");
        mapper.finishBarrels(order,AuthContext.getUserId());
        audit.log("DispatchAgreement","BARREL_HANDOVER",String.valueOf(order),"水站 "+station+" 确认本方实际交接；"+note,null);
    }
    @Transactional public void recallIfUnstarted(Long orderId) { if(policy.isEnabled())mapper.recall(orderId); }
    @Transactional public Map<String,Object> raiseToListed(Long orderId) {
        Orders order=locks.lockOrder(orderId);
        Map<String,Object> agreement=mapper.lock(orderId);
        if(agreement==null) return null;
        DispatchQuoteDTO dto=new DispatchQuoteDTO(); dto.setServiceAmount(nz(order.getWaterAmount()).add(nz(order.getDeliveryFee())).add(nz(order.getFloorFee())));
        dto.setBarrelMode(String.valueOf(agreement.get("barrel_mode"))); dto.setBarrelAmount(new BigDecimal(agreement.get("barrel_amount").toString()));
        dto.setNote("无人接单，归属站选择按挂牌水费加配送费用提高外包报价"); quote(orderId,dto);
        Map<String,Object> out=new LinkedHashMap<>(info(orderId)); out.put("changed",true); out.put("amount",dto.getServiceAmount()); return out;
    }
    private static BigDecimal nz(BigDecimal n){return n==null?BigDecimal.ZERO:n;}
}
