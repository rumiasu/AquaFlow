package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffEarningMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** 员工端本人历史工资只读查询；不接受员工/站别参数，不调用结算或付款。 */
@Service @RequiredArgsConstructor
public class SelfPayrollQueryService {
    private static final int SIZE=20;
    private final StaffPayrollMapper payrolls;
    private final StaffEarningMapper earnings;

    private Long actor() {
        if(!"staff".equals(AuthContext.getUserType())||AuthContext.getUserId()==null)
            throw new BusinessException("请使用员工账号查看本人工资");
        return AuthContext.getUserId();
    }

    /** 2026-11-02：原来仅20张且无出口，现在按本人和不可变id继续读，不使用offset。 */
    public Map<String,Object> page(Long beforeId) {
        Long staff=actor();
        if(beforeId!=null&&beforeId<=0)throw new BusinessException("工资记录位置无效，请重新读取");
        var rows=payrolls.listByStaffBefore(staff,beforeId,SIZE+1);
        boolean more=rows.size()>SIZE;var visible=rows.subList(0,Math.min(SIZE,rows.size()));
        var out=new LinkedHashMap<String,Object>();out.put("payrolls",visible);out.put("hasMore",more);
        out.put("nextBeforeId",more?visible.get(visible.size()-1).getId():null);return out;
    }

    /** 单据归属和明细读取都认本人；不存在与无权查看返回同一业务拒绝。 */
    public Map<String,Object> detail(Long id) {
        Long staff=actor();var p=id==null||id<=0?null:payrolls.getById(id);
        if(p==null||!staff.equals(p.getStaffId()))throw new BusinessException("工资单不存在或无权查看");
        return Map.of("payroll",p,"items",earnings.listByPayrollForStaff(id,staff));
    }
}
