package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.dto.ExceptionCloseoutDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
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
        mapper.ensureResolution(order.getId());
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
    public List<Map<String,Object>> list(Long station) {
        return policy.hasSchema()?mapper.list(station).stream().map(r->describe(r,station)).toList():List.of();
    }
    /** 专属只读分页入口；原撤销/解除权限、版本及幂等回执不变。 */
    @Transactional(readOnly = true)
    public Map<String,Object> page(Long station, String scope, Long beforeId, Long orderId) {
        AuthContext.requireManager();
        if (station == null || !station.equals(AuthContext.requireStationId())) throw new BusinessException("只能读取当前水站的拒付案件");
        if (!Set.of("ACTIVE","ALL").contains(scope) || beforeId != null && beforeId <= 0
                || orderId != null && (orderId <= 0 || beforeId != null)) throw new BusinessException("拒付清单筛选或分页编号不合法");
        if (!policy.hasSchema()) throw new BusinessException("拒付案件结构尚未就绪，请联系负责人核实");
        int limit = 50;
        List<Map<String,Object>> found = mapper.page(station,beforeId,"ACTIVE".equals(scope),orderId,limit + 1);
        if (orderId != null && found.isEmpty()) throw new BusinessException("未找到本站的拒付案件，请核实订单编号");
        boolean more = found.size() > limit;
        List<Map<String,Object>> records = found.subList(0,Math.min(limit,found.size())).stream().map(r->describe(r,station)).toList();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("stationId",station); out.put("scope",orderId == null ? scope : "LOOKUP"); out.put("limit",limit);
        out.put("items",records); out.put("nextBeforeId",more ? records.get(records.size()-1).get("order_id") : null);
        return out;
    }
    private Map<String,Object> describe(Map<String,Object> row,Long station) {
        Map<String,Object> out=new LinkedHashMap<>(row);
        boolean revoked=ExceptionActionEvidence.number(row,"judgmentRevoked")==1;
        boolean released=ExceptionActionEvidence.number(row,"assetFreezeReleased")==1;
        boolean confirmed=ExceptionActionEvidence.number(row,"asset_freeze_confirmed")==1;
        boolean pending=ExceptionActionEvidence.number(row,"paymentStatus")==1 && ExceptionActionEvidence.number(row,"orderStatus")!=5;
        out.put("canRevoke",!revoked && Objects.equals(station,((Number)row.get("debt_station_id")).longValue()));
        out.put("canRelease",confirmed && !released && Objects.equals(station,((Number)row.get("asset_station_id")).longValue()));
        out.put("canFreeze",pending && !revoked && !released && !confirmed && Objects.equals(station,((Number)row.get("asset_station_id")).longValue()));
        out.put("judgmentText",revoked?"拒付误判已撤销":"拒付判断保留");
        out.put("freezeText",released?"本案资产冻结已解除":confirmed?(pending?"本站资产冻结待复核":"原欠款已收口，本案冻结不再生效"):"资产站尚未确认冻结");
        out.put("debtText",pending?"原欠款仍待收取；纠错不免债":"原款状态已变化，请核对收款记录");
        return out;
    }

    /** 当事站站长读原判断及追加审计，不穿透归属站客户档案。 */
    public List<Map<String,Object>> history(Long order,Long station) {
        AuthContext.requireManager(); authorizeCase(mapper.getCase(order),station,null);
        return ExceptionActionEvidence.history(mapper.actions(order));
    }
    private void authorizeCase(Map<String,Object> row,Long station,String action) {
        AuthContext.requireManager();
        if(!Objects.equals(station,AuthContext.requireStationId()) || row==null)throw new BusinessException("拒付记录不存在或无权办理");
        long asset=ExceptionActionEvidence.number(row,"asset_station_id"),debt=ExceptionActionEvidence.number(row,"debt_station_id");
        if(("REVOKE".equals(action) && station!=debt) || ("RELEASE".equals(action) && station!=asset)
                || (action==null && station!=debt && station!=asset))throw new BusinessException("拒付记录不存在或无权办理");
    }

    /** 见拒付专项规格：撤销判断与解除资产冻结分权；始终不写收款/免债/赊账配置。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> resolve(Long order,Long station,String action,ExceptionCloseoutDTO dto) {
        if(!Set.of("REVOKE","RELEASE").contains(action))throw new BusinessException("不支持该拒付处理动作");
        authorizeCase(mapper.getCase(order),station,action);
        var input=ExceptionActionEvidence.input(dto,action);
        String actor="STAFF:"+AuthContext.getUserId()+":"+station;
        // 2026-10-08：原判断行串行化本案所有动作；RC重读已提交幂等结果，不锁空请求键。
        Map<String,Object> original=mapper.lockCase(order); authorizeCase(original,station,action);
        Map<String,Object> old=mapper.action(order,actor,input.key());
        if(old!=null){ExceptionActionEvidence.same(old,input);return ExceptionActionEvidence.receipt(old);}
        mapper.ensureResolution(order);
        var state=mapper.resolution(order);
        if(ExceptionActionEvidence.number(state,"version")!=input.version())throw new BusinessException("该拒付记录已有新处理，请刷新后再办理");
        int changed;
        if("REVOKE".equals(action))changed=mapper.revoke(order,input.version(),Objects.equals(original.get("asset_station_id"),original.get("debt_station_id")));
        else {
            if(ExceptionActionEvidence.number(original,"asset_freeze_confirmed")!=1)throw new BusinessException("本案尚未确认资产冻结，无需解除");
            changed=mapper.release(order,input.version());
        }
        if(changed!=1)throw new BusinessException("该拒付动作已办理或记录已变化，请刷新核对");
        if(mapper.appendAction(order,station,AuthContext.getUserId(),actor,action,input.reason(),input.key(),input.digest(),input.version())!=1)
            throw new BusinessException("处理记录未保存，本次办理已回滚");
        return ExceptionActionEvidence.receipt(mapper.action(order,actor,input.key()));
    }
    @Transactional
    public void confirmFreeze(Long order,Long station) {
        if (mapper.confirmFreeze(order,station,AuthContext.getUserId())!=1) throw new BusinessException("无法确认冻结：无权操作、已确认或原欠款已失效，请刷新台账");
    }
}
