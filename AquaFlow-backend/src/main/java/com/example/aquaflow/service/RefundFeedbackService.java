package com.example.aquaflow.service;

import com.example.aquaflow.dto.RefundFeedbackDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Append-only refund correspondence. This service has no payment/asset command dependencies. */
@Service
public class RefundFeedbackService {
    @Autowired private FeedbackMapper feedbackMapper;
    @Autowired private BarrelRecordMapper barrelRecordMapper;
    @Autowired private PaymentRecordMapper paymentRecordMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private Clock clock;
    @Autowired private RefundDisputeMapper disputes;

    public record Scope(Long customerId, Long stationId) {}
    /** 供争议沟通命令复用原对象判权；不能用客户绑定关系替代原款/资产责任。 */
    public Scope authorizedScope(String type,Long id) {Scope s=scope(type,id);authorize(s);return s;}
    private Scope scope(String type, Long id) {
        if (id == null || id <= 0) throw denied();
        if ("BARREL_RETURN".equals(type)) {
            BarrelRecord r=barrelRecordMapper.getById(id);
            if (r == null || !Integer.valueOf(2).equals(r.getType())) throw denied();
            return new Scope(r.getCustomerId(),r.getStationId());
        }
        PaymentRecord p=paymentRecordMapper.getById(id);
        if (p == null || p.getAmount() == null || p.getAmount().signum() <= 0
                || (!Integer.valueOf(2).equals(p.getStatus()) && !Integer.valueOf(3).equals(p.getStatus()))) throw denied();
        if ("ORDER_PAYMENT".equals(type) && p.getOrderId() != null) {
            Orders o=orderMapper.getById(p.getOrderId());
            if (o == null || !Objects.equals(o.getCustomerId(),p.getCustomerId())) throw denied();
            // Same authoritative order settlement station used by PaymentController.requireRefundStation.
            return new Scope(o.getCustomerId(),StationUtil.settleStation(o));
        }
        if ("TICKET_PAYMENT".equals(type) && p.getOrderId() == null && p.getTicketQty() != null && p.getTicketQty() > 0) {
            return new Scope(p.getCustomerId(),p.getStationId());
        }
        throw denied();
    }
    private void authorize(Scope s) {
        if (s.customerId() == null || s.stationId() == null) throw denied();
        if ("customer".equals(AuthContext.getUserType())) {
            if (!s.customerId().equals(AuthContext.requireCustomerId())) throw denied();
        } else if (!AuthContext.isManager() || !s.stationId().equals(AuthContext.requireStationId())) throw denied();
    }
    private BusinessException denied() { return new BusinessException("退款记录不存在或无权访问"); }
    private String text(String value,int max) {
        String t=value == null ? "" : value.trim();
        if (t.length()>max) throw new BusinessException("退款说明或联系方式过长");
        return t;
    }
    private String digest(String content,String contact) {
        try {
            String value=content.length()+":"+content+contact.length()+":"+contact;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Feedback same(Feedback old,String digest) {
        if (!digest.equals(old.getRequestDigest())) throw new BusinessException("同一提交编号不能修改说明，请另写一条补充记录");
        return old;
    }
    @Transactional
    public Feedback append(RefundFeedbackDTO dto) {
        Scope s=scope(dto.getRefundType(),dto.getRefundId()); authorize(s); // Always before replay, including late retries.
        String key=text(dto.getIdempotencyKey(),64);
        if (key.isEmpty()) throw new BusinessException("退款说明提交编号不能为空");
        String content=text(dto.getContent(),1000),contact=text(dto.getContact(),100);
        String digest=digest(content,contact);
        String actor=("customer".equals(AuthContext.getUserType()) ? "CUSTOMER:" : "STAFF:")+AuthContext.getUserId();
        Feedback old=feedbackMapper.getRefundNote(actor,dto.getRefundType(),dto.getRefundId(),key);
        if (old != null) return same(old,digest);
        Feedback note=new Feedback(); note.setCustomerId(s.customerId());
        if (!"customer".equals(AuthContext.getUserType())) note.setStaffId(AuthContext.getUserId());
        note.setCategory("退款说明");note.setContent(content.isEmpty() ? "退款补充记录（暂无说明）" : content);
        note.setContact(contact);note.setAnonymous(false);note.setCreateTime(LocalDateTime.now(clock));
        note.setRefundType(dto.getRefundType());note.setRefundId(dto.getRefundId());note.setResponsibleStationId(s.stationId());
        note.setActorKey(actor);note.setIdempotencyKey(key);note.setRequestDigest(digest);
        // Unique-key upsert only returns the original id. It never overwrites content, author, time or station.
        feedbackMapper.insertRefundNote(note);
        // In REPEATABLE READ an earlier absent-key snapshot cannot see a concurrent winner.
        // The post-upsert current read sees its original facts; no absent-key gap lock before insertion.
        return same(feedbackMapper.getRefundNoteForUpdate(actor,dto.getRefundType(),dto.getRefundId(),key),digest);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> thread(String type,Long id) {
        Scope s=scope(type,id);authorize(s);
        Feedback label=new Feedback();label.setRefundType(type);label.setRefundId(id);
        var notes=feedbackMapper.listRefundNotes(type,id,s.customerId(),
                "customer".equals(AuthContext.getUserType()) ? null : s.stationId());
        var dispute=disputes.get(type,id);
        if(dispute!=null && (!Objects.equals(dispute.get("customerId"),s.customerId()) || !Objects.equals(dispute.get("responsibleStationId"),s.stationId())))
            throw new BusinessException("退款责任记录已变化，请由原责任站核实");
        return Map.of("refundType",type,"refundId",id,"objectText",label.getRefundObjectText(),"notes",notes,
                "dispute",RefundDisputeService.view(dispute,disputes.actions(type,id,s.customerId(),s.stationId())));
    }
    public List<Feedback> managerList() {
        if (!AuthContext.isManager()) throw denied();
        Long station=AuthContext.requireStationId();
        return feedbackMapper.listCustomerFeedbackByStation(station).stream().filter(f -> {
            if (f.getRefundType() == null) return true;
            try {Scope s=scope(f.getRefundType(),f.getRefundId());return station.equals(s.stationId()) && Objects.equals(s.customerId(),f.getCustomerId());}
            catch (BusinessException unavailable) {return false;}
        }).toList();
    }
    public Map<String,Object> options(int page) {
        if (page<1 || page>1_000_000) throw new BusinessException("退款记录页码无效");
        var all=feedbackMapper.refundOptions(AuthContext.requireCustomerId(),(page-1)*200);
        return Map.of("options",all.size()>200 ? all.subList(0,200) : all,"hasMore",all.size()>200,"page",page);
    }
}
