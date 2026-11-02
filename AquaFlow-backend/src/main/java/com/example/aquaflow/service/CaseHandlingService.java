package com.example.aquaflow.service;

import com.example.aquaflow.dto.ExceptionCloseoutDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** Non-financial case workflow: no dependency on payments, bucket or ticket write services. */
@Service @RequiredArgsConstructor
public class CaseHandlingService {
    private final CaseHandlingMapper mapper;
    private final FeedbackMapper feedback;
    private final OrderBarrelExceptionMapper exceptions;

    private void ready() {
        if(mapper.schemaReady()!=1)throw new BusinessException("处理记录尚未准备好，请联系负责人核对服务更新");
    }
    private static boolean positive(Integer n){return n!=null&&n!=0;}
    private static boolean amount(BigDecimal n){return n!=null&&n.signum()!=0;}
    private static boolean canResolve(OrderBarrelException e) {
        return "STAFF_RECORDED".equals(e.getStatus()) && !"CUSTOMER_REFUSE".equals(e.getCategory())
            && !positive(e.getDiscrepancy()) && !positive(e.getWaterOwed())
            && !positive(e.getSuggestedTicketQty()) && !amount(e.getSuggestedCashAmount())
            && !positive(e.getRefundTicketQty()) && !amount(e.getRefundCashAmount()) && !positive(e.getAdjustAssetQty());
    }
    private void authorizeException(OrderBarrelException e) {
        AuthContext.requireManager();
        if(e==null||!AuthContext.requireStationId().equals(e.getStationId()))throw new BusinessException("异常不存在或无权办理");
    }
    private Long authorizeFeedback(Feedback f) {
        if(f==null||f.getCustomerId()==null||f.getRefundType()!=null)throw new BusinessException("普通反馈不存在或无权办理");
        if("customer".equals(AuthContext.getUserType())) {
            if(!f.getCustomerId().equals(AuthContext.requireCustomerId()))throw new BusinessException("反馈不存在或无权查看");
            return null;
        }
        AuthContext.requireManager();Long station=AuthContext.requireStationId();
        if(mapper.related(f.getCustomerId(),station)!=1)throw new BusinessException("反馈不存在或不属于本水站");
        return station;
    }
    private static long version(List<Map<String,Object>> h) {
        return h.stream().mapToLong(x->((Number)x.get("version")).longValue()).max().orElse(0);
    }
    private static boolean closed(List<Map<String,Object>> h) {
        return !h.isEmpty()&&"CLOSE".equals(h.get(h.size()-1).get("action"));
    }
    private Map<String,Object> view(String kind,Long id,Long station) {
        var history=mapper.history(kind,id,station);var out=new LinkedHashMap<String,Object>();
        out.put("kind",kind);out.put("caseId",id);out.put("stationId",station);out.put("version",version(history));
        var visible=history.stream().map(row->{var action=new LinkedHashMap<String,Object>(row);
            action.put("actionText",switch(String.valueOf(row.get("action"))){case "VERIFY"->"核实结案（无需补偿）";case "REPLY"->"水站回复";case "CLOSE"->"回复并结案";default->"处理动作待核实";});return action;}).toList();
        out.put("actions",visible);out.put("closed",closed(history));
        return out;
    }
    public Map<String,Object> exceptionView(Long id) {
        var e=exceptions.getById(id);authorizeException(e);ready();var out=view("EXCEPTION",id,e.getStationId());
        out.put("canResolve",canResolve(e));out.put("status",e.getStatus());out.put("statusText",e.getStatusText());return out;
    }
    public Map<String,Object> feedbackView(Long id) {
        var f=feedback.getById(id);var station=authorizeFeedback(f);ready();var out=view("FEEDBACK",id,station);
        // Each station has its own handling line. Never label one station's closure as global closure.
        if(station==null){out.put("version",0L);out.remove("closed");}
        out.put("canReply",station!=null&&!Boolean.TRUE.equals(out.get("closed")));
        out.put("canClose",out.get("canReply"));return out;
    }
    private Map<String,Object> replay(String kind,Long id,Long station,ExceptionActionEvidence.Input input) {
        if(!input.key().matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException("办理编号格式无效，请重新核对原请求");
        var r=mapper.receipt(kind,id,station,AuthContext.getUserId(),input.key());
        if(r!=null){ExceptionActionEvidence.same(r,input);return ExceptionActionEvidence.receipt(r);}return null;
    }
    private Map<String,Object> append(String kind,Long id,Long station,String action,ExceptionActionEvidence.Input input) {
        if(mapper.append(kind,id,station,AuthContext.getUserId(),input.version()+1,action,input.reason(),input.key(),input.digest())!=1)
            throw new BusinessException("处理记录未保存，本次办理已回滚");
        return Objects.requireNonNull(replay(kind,id,station,input));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> resolve(Long id,ExceptionCloseoutDTO dto) {
        AuthContext.requireManager();ready();var input=ExceptionActionEvidence.input(dto,"VERIFY");
        if(input.reason().length()>500)throw new BusinessException("核实理由最多500字");
        var e=exceptions.getByIdForUpdate(id);authorizeException(e);Long station=e.getStationId();
        var old=replay("EXCEPTION",id,station,input);if(old!=null)return old;
        if(version(mapper.history("EXCEPTION",id,station))!=input.version()||!canResolve(e))
            throw new BusinessException("异常已有变化或仍有差异、补偿待办，请刷新核对后办理");
        if(exceptions.updateDecisionIf(id,"STAFF_RECORDED","VERIFY",null,null,null,null,input.reason(),"VERIFIED")!=1)
            throw new BusinessException("异常状态已变化，请刷新核对");
        return append("EXCEPTION",id,station,"VERIFY",input);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> feedbackAct(Long id,ExceptionCloseoutDTO dto,boolean close) {
        AuthContext.requireManager();ready();String action=close?"CLOSE":"REPLY";var input=ExceptionActionEvidence.input(dto,action);
        var f=mapper.lockFeedback(id);Long station=authorizeFeedback(f);
        var old=replay("FEEDBACK",id,station,input);if(old!=null)return old;
        var history=mapper.history("FEEDBACK",id,station);
        if(version(history)!=input.version()||closed(history))throw new BusinessException("反馈已有新回复或已结案，请刷新核对");
        return append("FEEDBACK",id,station,action,input);
    }
}
