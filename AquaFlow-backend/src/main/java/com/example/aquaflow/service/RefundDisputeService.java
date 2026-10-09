package com.example.aquaflow.service;

import com.example.aquaflow.dto.RefundDisputeDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 退款争议沟通状态机；无资金、押金、桶账或水票写服务依赖。 */
@Service @RequiredArgsConstructor
public class RefundDisputeService {
    private final RefundDisputeMapper mapper;
    private final RefundFeedbackService access;
    private final PaymentRecordMapper payments;
    private final ConsumptionRefundMapper locks;

    public static Map<String,Object> view(Map<String,Object> row,List<Map<String,Object>> history) {
        Map<String,Object> out=row==null?new LinkedHashMap<>():new LinkedHashMap<>(row);
        String status=row==null?"NONE":String.valueOf(row.get("status"));
        out.put("status",status); out.put("statusText",switch(status){case "NONE"->"尚未提出退款争议";case "OPEN"->"水站处理中";case "CLOSED"->"水站已登记结果并结案，可再次提出异议";default->"处理状态待核实";});
        out.put("version",row==null?0L:row.get("version"));out.put("canOpen","NONE".equals(status)||"CLOSED".equals(status));
        out.put("canClose","OPEN".equals(status));out.put("actions",ExceptionActionEvidence.history(history));
        return out;
    }
    public List<Map<String,Object>> list(int page) {
        if(page<1 || page>1_000_000)throw new BusinessException("争议记录页码无效");
        Long customer=null,station=null;
        if("customer".equals(AuthContext.getUserType()))customer=AuthContext.requireCustomerId();
        else {AuthContext.requireManager();station=AuthContext.requireStationId();}
        List<Map<String,Object>> out=new ArrayList<>();
        for(var row:mapper.list(customer,station,(page-1)*200)) {
            try {
                var scope=access.authorizedScope(String.valueOf(row.get("refundType")),((Number)row.get("refundId")).longValue());
                checkScope(row,scope);
                var item=view(row,List.of());
                var label=new com.example.aquaflow.entity.Feedback();label.setRefundType(String.valueOf(row.get("refundType")));label.setRefundId(((Number)row.get("refundId")).longValue());
                item.put("objectText",label.getRefundObjectText()); out.add(item);
            } catch(BusinessException unavailable) {
                // 2026-10-08：责任/原款疑义不能吞成“没有争议”，也不能借快照放宽权限。
                throw new BusinessException("部分退款争议暂时无法核对，请核实原款与责任站后重试");
            }
        }
        return out;
    }
    private static void checkScope(Map<String,Object> row,RefundFeedbackService.Scope scope) {
        if(row!=null && (!Objects.equals(row.get("customerId"),scope.customerId()) || !Objects.equals(row.get("responsibleStationId"),scope.stationId())))
            throw new BusinessException("退款责任记录已变化，请由原责任站核实");
    }
    /** 顾客提出/重提异议或责任站站长结案；重放先于当前状态校验，资金原事实永久保留。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> act(RefundDisputeDTO dto,boolean close) {
        if(close)AuthContext.requireManager();
        else if(!"customer".equals(AuthContext.getUserType()))throw new BusinessException("仅本人可提出退款异议");
        var scope=access.authorizedScope(dto.getRefundType(),dto.getRefundId());
        var input=ExceptionActionEvidence.input(dto,close?"CLOSE":"OPEN");
        String actor=(close?"STAFF:":"CUSTOMER:")+AuthContext.getUserId()+(close?":"+AuthContext.requireStationId():"");
        // 与实际消费退款相同：先订单、再原款；退桶只锁原申请。不在空争议键上拿间隙锁。
        if("BARREL_RETURN".equals(dto.getRefundType())) {
            if(mapper.lockReturn(dto.getRefundId())==null)throw new BusinessException("退款申请不存在或无权办理");
        } else {
            var payment=payments.getById(dto.getRefundId());
            if(payment==null)throw new BusinessException("原款不存在或无权办理");
            if(payment.getOrderId()!=null)locks.lockOrder(payment.getOrderId());
            locks.lockPayment(dto.getRefundId());
        }
        scope=access.authorizedScope(dto.getRefundType(),dto.getRefundId());
        var old=mapper.action(actor,dto.getRefundType(),dto.getRefundId(),input.key());
        if(old!=null){ExceptionActionEvidence.same(old,input);return ExceptionActionEvidence.receipt(old);}
        var row=mapper.get(dto.getRefundType(),dto.getRefundId());checkScope(row,scope);
        long version=row==null?0:ExceptionActionEvidence.number(row,"version");
        if(version!=input.version())throw new BusinessException("争议已有新处理，请刷新后再办理");
        String action;
        int changed;
        if(close) {
            if(row==null || !"OPEN".equals(row.get("status")))throw new BusinessException("当前没有待处理的退款争议");
            action="CLOSE";changed=mapper.transition(dto.getRefundType(),dto.getRefundId(),scope.customerId(),scope.stationId(),version,"OPEN","CLOSED",input.reason());
        } else if(row==null) {action="OPEN";changed=mapper.open(dto.getRefundType(),dto.getRefundId(),scope.customerId(),scope.stationId());}
        else {
            if(!"CLOSED".equals(row.get("status")))throw new BusinessException("该退款争议仍在处理，可继续追加说明");
            action="REOPEN";changed=mapper.transition(dto.getRefundType(),dto.getRefundId(),scope.customerId(),scope.stationId(),version,"CLOSED","OPEN",input.reason());
        }
        if(changed!=1)throw new BusinessException("争议状态已变化，请刷新后核对");
        if(mapper.appendAction(dto.getRefundType(),dto.getRefundId(),scope.customerId(),scope.stationId(),actor,AuthContext.getUserId(),action,input.reason(),input.key(),input.digest(),version)!=1)
            throw new BusinessException("处理记录未保存，本次办理已回滚");
        return ExceptionActionEvidence.receipt(mapper.action(actor,dto.getRefundType(),dto.getRefundId(),input.key()));
    }
}
