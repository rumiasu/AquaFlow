package com.example.aquaflow.service;

import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 只核实已有实物凭据及关闭异常，不写桶账/押金/水票，不把净额当逐单分摊。 */
@Service @RequiredArgsConstructor
public class BarrelExceptionRecoveryService {
    private final OrderBarrelExceptionMapper exceptions;
    private final OrderMapper orders;
    private final BarrelRecordMapper records;
    private final CaseHandlingMapper actions;
    private final CustomerBarrelOverMapper overs;

    /** 已校验的原配送及归还凭据；returned 不以当前净欠桶为依据。 */
    public record Evidence(boolean supported, boolean returned, List<Long> products, List<Long> sources, List<Long> receipts) {
        static Evidence unknown() { return new Evidence(false,false,List.of(),List.of(),List.of()); }
        public String description() { return "原配送凭据 " + sources + "；实际归还凭据 " + receipts; }
    }

    private static boolean zero(Integer n) { return n==null || n==0; }
    private static boolean zero(BigDecimal n) { return n==null || n.signum()==0; }
    static boolean eligible(OrderBarrelException e) {
        return e!=null && "STAFF_RECORDED".equals(e.getStatus()) && "RETURN_SHORT".equals(e.getCategory())
            && "SHORT_RETURN".equals(e.getType()) && e.getDiscrepancy()!=null && e.getDiscrepancy()>0
            && e.getOrderId()!=null && e.getCustomerId()!=null && e.getStationId()!=null
            && zero(e.getWaterOwed()) && zero(e.getRefundTicketQty()) && zero(e.getRefundCashAmount())
            && zero(e.getAdjustAssetQty()) && zero(e.getSuggestedTicketQty()) && zero(e.getSuggestedCashAmount())
            && e.getDeliveryQty()!=null && e.getReturnQty()!=null && e.getDeliveryQty()>=0 && e.getReturnQty()>=0
            && (long)e.getDeliveryQty()-e.getReturnQty()==e.getDiscrepancy()
            && e.getAdjustProductId()==null && e.getDecidedAt()==null && e.getExecutedAt()==null
            && (e.getManagerNote()==null || e.getManagerNote().isBlank())
            && (e.getManagerAction()==null || e.getManagerAction().isBlank());
    }

    /** 2026-11-05：原纯还桶没有关联异常。原订单逐商品来源和连续实收链缺一不可，历史缺证不猜。 */
    public Evidence evidence(OrderBarrelException e) {
        if(!eligible(e) || actions.schemaReady()!=1 || exceptions.shortReturnsOfOrder(e.getOrderId())!=1) return Evidence.unknown();
        Orders order=orders.getById(e.getOrderId());
        if(order==null || !Objects.equals(order.getCustomerId(),e.getCustomerId())
                || !Objects.equals(order.getStationId(),e.getStationId())
                || !(Integer.valueOf(3).equals(order.getStatus()) || Integer.valueOf(4).equals(order.getStatus()))
                || !actions.history("EXCEPTION",e.getId(),e.getStationId()).isEmpty())return Evidence.unknown();
        List<BarrelRecord> sourceRows=records.deliveryEvidence(e.getOrderId(),e.getCustomerId(),e.getStationId());
        return prove(e,sourceRows, r->records.exactDeliveryEvidence(r.getId())==1,
                r->records.historyAfter(e.getCustomerId(),e.getStationId(),r.getProductId(),r.getId()));
    }

    /** 纯判据供回归验证：正负差异不能互抵、部分归还不擅自 FIFO 分配给某一订单。 */
    static Evidence prove(OrderBarrelException e,List<BarrelRecord> rows,
                          java.util.function.Predicate<BarrelRecord> exact,
                          java.util.function.Function<BarrelRecord,List<BarrelRecord>> history) {
        if(!eligible(e) || rows.isEmpty()) return Evidence.unknown();
        List<Long> products=new ArrayList<>(), sources=new ArrayList<>(), receipts=new ArrayList<>();
        Set<Long> seen=new HashSet<>();long difference=0,delivered=0,actual=0;boolean returned=true;
        for(BarrelRecord r:rows) {
            if(!scope(e,r) || !Objects.equals(r.getRelatedOrderId(),e.getOrderId()) || !valid(r,8)
                    || !seen.add(r.getProductId()) || r.getOverBefore()<0 || r.getOverAfter()<r.getOverBefore())
                return Evidence.unknown();
            int delta=r.getOverAfter()-r.getOverBefore();
            // 新付押金首次领取会增加实物而不形成少回；不猜测未留存的预期回桶量。
            if(r.getDeliveredQty()==null || r.getReturnedQty()==null
                    || r.getDeliveredQty()<0 || r.getReturnedQty()<0
                    || (long)delta!=(long)r.getDeliveredQty()-r.getReturnedQty())return Evidence.unknown();
            delivered+=r.getDeliveredQty();actual+=r.getReturnedQty();
            if(delta==0)continue;
            if(!exact.test(r))return Evidence.unknown();
            difference+=delta;products.add(r.getProductId());sources.add(r.getId());
            Long receipt=clearingReceipt(e,r,history.apply(r),exact);
            if(receipt==null)returned=false;else receipts.add(receipt);
        }
        if(difference!=e.getDiscrepancy() || delivered!=e.getDeliveryQty() || actual!=e.getReturnQty() || products.isEmpty())return Evidence.unknown();
        return new Evidence(true,returned,List.copyOf(products),List.copyOf(sources),List.copyOf(receipts));
    }

    private static boolean scope(OrderBarrelException e,BarrelRecord r) {
        return r!=null && r.getId()!=null && r.getProductId()!=null
            && Objects.equals(e.getCustomerId(),r.getCustomerId()) && Objects.equals(e.getStationId(),r.getStationId());
    }
    private static boolean valid(BarrelRecord r,int type) {
        return Integer.valueOf(type).equals(r.getType()) && Integer.valueOf(3).equals(r.getStatus())
            && r.getOverBefore()!=null && r.getOverAfter()!=null && r.getAdjustmentId()==null
            && (type!=8 || r.getQuantity()!=null && r.getQuantity().equals(r.getDeliveredQty()))
            && (type!=7 || r.getDepositRefund()!=null && r.getDepositRefund().signum()==0
                && Integer.valueOf(0).equals(r.getDeliveredQty()) && Integer.valueOf(0).equals(r.getReturnedQty()));
    }
    private static Long clearingReceipt(OrderBarrelException e,BarrelRecord source,List<BarrelRecord> history,java.util.function.Predicate<BarrelRecord> exact) {
        int previous=source.getOverAfter();long lastId=source.getId();
        for(BarrelRecord r:history) {
            if(!scope(e,r) || !Objects.equals(source.getProductId(),r.getProductId()) || r.getId()<=lastId
                    || r.getOverBefore()==null || r.getOverBefore()!=previous || r.getOverAfter()==null)return null;
            if(valid(r,7)) {
                if(r.getQuantity()==null || r.getQuantity()<=0 || (long)previous-r.getOverAfter()!=r.getQuantity())return null;
                if(previous>0 && r.getOverAfter()<=0)return r.getId();
            } else if(valid(r,8)) {
                // 不将另一单比例分摊的多回、押金抵扣或无实收来源的负向变化当作本单归还。
                if(r.getOverAfter()<previous || !exact.test(r) || r.getRelatedOrderId()==null
                        || r.getDeliveredQty()==null || r.getReturnedQty()==null
                        || r.getDeliveredQty()<0 || r.getReturnedQty()<0
                        || (long)r.getOverAfter()-previous>(long)r.getDeliveredQty()-r.getReturnedQty())return null;
            } else return null;
            previous=r.getOverAfter();lastId=r.getId();
        }
        return null;
    }

    /** 仅展示仍有同商品欠桶的办理入口，不能作为结案依据。 */
    public boolean canReturnEmpty(OrderBarrelException e,Evidence evidence) {
        return evidence.supported() && !evidence.returned() && evidence.products().stream().anyMatch(p->{
            CustomerBarrelOver over=overs.get(e.getCustomerId(),e.getStationId(),p);
            return over!=null && over.getOverQty()!=null && over.getOverQty()>0;
        });
    }
    /** 区分可核实的历史归还与缺来源记录，避免引导重复归还。 */
    public String hint(OrderBarrelException e,Evidence proof) {
        if("RETURNED".equals(e.getStatus()))return "已按原配送及实际归还凭据结案，原少回数量保留供核对。";
        if(!"STAFF_RECORDED".equals(e.getStatus()) || !"RETURN_SHORT".equals(e.getCategory()))return "";
        if(proof.returned())return "已找到完整归还凭据，可核实归还并结案。";
        if(proof.supported())return "请核对原订单对应商品的实际归还；部分归还不会自动分配到某一异常。";
        return "原商品或归还凭据尚不能完整核实，请核对原订单和桶账；不能仅按当前欠桶数量结案。";
    }

    /** 在桶账之前锁定全部候选；与人工处理保持异常 → 桶账的锁顺序。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public List<OrderBarrelException> lockCandidates(Long customer,Long station) {
        return exceptions.pendingReturnsForUpdate(customer,station);
    }

    /** 调用方已持候选异常锁；与 type=7 流水同事务，任一 CAS/回执失败整体回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeReturned(List<OrderBarrelException> candidates,Long actor,Set<Long> newReceipts) {
        for(OrderBarrelException e:candidates) {
            Evidence proof=evidence(e);
            if(!proof.returned() || Collections.disjoint(proof.receipts(),newReceipts))continue;
            if(actions.schemaReady()!=1)throw new BusinessException("归还处理记录尚未准备好，请联系负责人核对后重试");
            List<Map<String,Object>> history=actions.history("EXCEPTION",e.getId(),e.getStationId());
            // 已有处理历史交由原办理继续，不能覆盖其它业务决定。
            if(!history.isEmpty())continue;
            String reason="实际还桶后同步结案；"+proof.description();
            if(reason.length()>500)continue;
            if(exceptions.updateDecisionIf(e.getId(),"STAFF_RECORDED","RETURN_EMPTY",null,null,null,null,reason,"RETURNED")!=1)
                throw new BusinessException("异常状态已变化，本次还桶未登记，请刷新核对");
            if(actions.append("EXCEPTION",e.getId(),e.getStationId(),actor,1,"RETURN_EMPTY",reason,
                    "return_empty_"+e.getId(),"returned:"+e.getId())!=1)
                throw new BusinessException("归还处理记录未保存，本次还桶登记未生效");
        }
    }
}
