package com.example.aquaflow.service;

import com.example.aquaflow.dto.ExceptionCloseoutDTO;
import com.example.aquaflow.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 纯输入与审计工具；无资金/桶/票写依赖。 */
public final class ExceptionActionEvidence {
    private ExceptionActionEvidence() {}
    public record Input(String key,String reason,long version,String digest) {}

    /** 包含期望版本的指纹防止把旧请求换成新裁定；重放必须先于状态迁移校验。 */
    public static Input input(ExceptionCloseoutDTO dto,String action) {
        if(dto==null || dto.getExpectedVersion()==null || dto.getExpectedVersion()<0)
            throw new BusinessException("请刷新记录后再办理");
        String key=dto.getIdempotencyKey()==null?"":dto.getIdempotencyKey().trim();
        String reason=dto.getReason()==null?"":dto.getReason().trim();
        if(key.isEmpty() || key.length()>64)throw new BusinessException("办理编号不能为空或超过64字");
        if(reason.isEmpty() || reason.length()>1000)throw new BusinessException("请填写办理理由或结果（最多1000字）");
        String value=action+":"+dto.getExpectedVersion()+":"+reason.length()+":"+reason;
        try {
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            return new Input(key,reason,dto.getExpectedVersion(),digest);
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void same(Map<String,Object> receipt,Input input) {
        if(!input.digest().equals(receipt.get("requestDigest")))throw new BusinessException("同一办理编号不能修改理由、动作或版本，请先核对原结果");
    }
    public static Map<String,Object> receipt(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>(row);
        result.remove("requestDigest"); result.remove("actorKey"); result.remove("idempotencyKey");
        return result;
    }
    public static long number(Map<String,Object> row,String name) {
        Object value=row.get(name); return value instanceof Number n?n.longValue():Boolean.TRUE.equals(value)?1:0;
    }
    public static List<Map<String,Object>> history(List<Map<String,Object>> rows) {
        return rows.stream().map(r->{Map<String,Object> view=receipt(r);String action=String.valueOf(r.get("action"));
            view.put("actionText",switch(action){case "REVOKE"->"撤销拒付误判";case "RELEASE"->"解除本案资产冻结";
                case "OPEN"->"提出退款异议";case "REOPEN"->"重新提出退款异议";case "CLOSE"->"水站登记结果并结案";default->"未知处理动作";});return view;}).toList();
    }
}
