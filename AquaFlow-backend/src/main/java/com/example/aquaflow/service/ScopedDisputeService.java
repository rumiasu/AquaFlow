package com.example.aquaflow.service;

import com.example.aquaflow.dto.RefusalFreezeScopeDTO;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 争议限额不扣债、不扣押金；实际现金仍沿退还单及原款凭据登记。 */
@Service
public class ScopedDisputeService {
    private final ScopedRefusalRefundMapper scopes;
    private final ConfirmedRefusalMapper cases;
    private final PlatformTransactionManager transactions;
    private final tools.jackson.databind.ObjectMapper json;
    public ScopedDisputeService(ScopedRefusalRefundMapper scopes,ConfirmedRefusalMapper cases,PlatformTransactionManager transactions,tools.jackson.databind.ObjectMapper json) {
        this.scopes=scopes;this.cases=cases;this.transactions=transactions;this.json=json;
    }
    public record Quote(BigDecimal frozen,BigDecimal productRemaining,BigDecimal payable,boolean unknown) {}

    /** 外层实退持押金账户锁；独立只读RC读取冻结最新提交，不受外层旧快照影响。 */
    public Quote quote(Long customer,Long station,Long product,BigDecimal requested) {
        var tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setReadOnly(true);
        return tx.execute(status -> readQuote(customer,station,product,requested));
    }
    private Quote readQuote(Long customer,Long station,Long product,BigDecimal requested) {
        BigDecimal frozen=BigDecimal.ZERO; boolean unknown=false;
        for(var row:scopes.active(customer,station)) {
            Long order=((Number)row.get("order_id")).longValue();
            var lines=scopes.scope(order);
            if(!lines.isEmpty() && lines.stream().noneMatch(line->Objects.equals(((Number)line.get("productId")).longValue(),product)))continue;
            if(!(row.get("originalCustomerId") instanceof Number originalCustomer) || !(row.get("originalStationId") instanceof Number originalStation)
                    || !Objects.equals(originalCustomer.longValue(),customer) || !Objects.equals(originalStation.longValue(),station)) {
                unknown=true;continue;
            }
            BigDecimal debt=(BigDecimal)row.get("debtAmount");
            if(!lines.isEmpty()) {
                BigDecimal total=lines.stream().map(r->(BigDecimal)r.get("amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
                for(var line:lines)if(Objects.equals(((Number)line.get("productId")).longValue(),product)) {
                    if(debt==null || debt.signum()<0 || total.compareTo(debt)>0)unknown=true;
                    else frozen=frozen.add((BigDecimal)line.get("amount"));
                }
                continue;
            }
            Set<Long> products=products(order);
            if(!products.isEmpty() && !products.contains(product))continue;
            // 原案是全单拒付时，仅一个明确商品可直接沿原债权上限。混合/缺证据不猜金额分摊。
            if(products.size()!=1 || debt==null || debt.signum()<0)unknown=true;
            else frozen=frozen.add(debt);
        }
        BigDecimal remaining=scopes.productMoney(customer,station,product).subtract(scopes.pendingPaid(customer,station,product));
        if(remaining.signum()<0)throw new BusinessException("商品退款台账待核实，本次未办理");
        remaining=remaining.min(scopes.accountBalance(customer,station));
        BigDecimal held=frozen.min(remaining);
        BigDecimal payable=unknown?BigDecimal.ZERO:requested.min(remaining.subtract(held)).max(BigDecimal.ZERO);
        return new Quote(held,remaining,payable,unknown);
    }
    private Set<Long> products(Long order) {
        Set<Long> ids=new TreeSet<>();
        for(var line:scopes.products(order))if(line.get("productId") instanceof Number n && n.longValue()>0)ids.add(n.longValue());
        if(ids.isEmpty())ids.addAll(scopes.legacyProduct(order));
        return ids;
    }
    public Map<String,Object> metadata(Long order) {
        var result=new LinkedHashMap<String,Object>();
        var products=new ArrayList<>(scopes.products(order));
        if(products.isEmpty()){products.addAll(scopes.legacyProductInfo(order));result.put("legacyProductReference",true);}
        var lines=new ArrayList<Map<String,Object>>();
        for(var source:scopes.scope(order)) {
            var line=new LinkedHashMap<>(source);Object id=source.get("productId");
            line.put("productName",products.stream().filter(p->Objects.equals(p.get("productId"),id)).map(p->p.get("productName")).filter(Objects::nonNull).findFirst().orElse("原单商品 #"+id));lines.add(line);
        }
        result.put("freezeScope",lines);result.put("scopeProducts",products);return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> configure(Long order,Long station,RefusalFreezeScopeDTO dto) {
        AuthContext.requireManager();var row=cases.getCase(order);
        if(row==null || !Objects.equals(station,AuthContext.requireStationId()) || !Objects.equals(((Number)row.get("asset_station_id")).longValue(),station))
            throw new BusinessException("只有本案资产站站长可核对冻结范围");
        if(dto==null || dto.getExpectedVersion()==null || dto.getExpectedVersion()<0 || dto.getItems()==null || dto.getItems().isEmpty() || dto.getItems().size()>200)
            throw new BusinessException("请核对原案版本及争议商品金额");
        String key=key(dto.getIdempotencyKey());var amounts=new TreeMap<Long,BigDecimal>();
        for(var item:dto.getItems()) {
            if(item==null || item.getProductId()==null || item.getProductId()<=0 || item.getAmount()==null || item.getAmount().signum()<=0 || item.getAmount().scale()>2 || item.getAmount().compareTo(new BigDecimal("99999999.99"))>0 || amounts.putIfAbsent(item.getProductId(),item.getAmount().setScale(2))!=null)
                throw new BusinessException("争议范围须为不重复商品及精确正数金额");
        }
        String actor="STAFF:"+AuthContext.getUserId()+":"+station;
        String reason=dto.getReason()==null || dto.getReason().isBlank()?"本站核对本案争议商品及冻结金额":dto.getReason().trim();
        if(reason.length()>1000)throw new BusinessException("核对说明最多1000字");
        String digest=digest("SCOPE|"+dto.getExpectedVersion()+"|"+reason+"|"+amounts);
        var current=scopes.lockOrder(order);
        scopes.lockAccount(((Number)row.get("customer_id")).longValue(),station);
        row=cases.lockCase(order);
        var old=cases.action(order,actor,key);
        if(old!=null) { if(!digest.equals(old.get("requestDigest")))throw new BusinessException("同一办理编号的范围或版本不能改变");return ExceptionActionEvidence.receipt(old); }
        cases.ensureResolution(order);var state=cases.resolution(order);
        if(ExceptionActionEvidence.number(state,"version")!=dto.getExpectedVersion() || ExceptionActionEvidence.number(state,"assetFreezeReleased")!=0)
            throw new BusinessException("本案已变化或已解冻，请核对原结果");
        // 当前订单状态由原CAS再次锁定核对；不允许还清/取消后新增冻结事实。
        if(current==null || ExceptionActionEvidence.number(current,"paymentStatus")!=1 || ExceptionActionEvidence.number(current,"orderStatus")==5 || ExceptionActionEvidence.number(state,"judgmentRevoked")!=0)
            throw new BusinessException("原欠款已失效或误判已撤销，不能新增范围冻结");
        if(!Objects.equals(((Number)current.get("originalCustomerId")).longValue(),((Number)row.get("customer_id")).longValue()) || !Objects.equals(((Number)current.get("originalStationId")).longValue(),station))
            throw new BusinessException("原案客户或资产站证据待核实，本次不改范围");
        BigDecimal debt=(BigDecimal)current.get("debtAmount");
        Set<Long> allowed=products(order);
        if(!allowed.containsAll(amounts.keySet()) || amounts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(debt)>0)
            throw new BusinessException("冻结范围超过原案商品或有效欠款金额");
        if(ExceptionActionEvidence.number(row,"asset_freeze_confirmed")==0 && cases.confirmFreeze(order,station,AuthContext.getUserId())!=1)
            throw new BusinessException("原欠款状态已变化，本次未冻结");
        if(scopes.advance(order,dto.getExpectedVersion())!=1)throw new BusinessException("本案版本已变化");
        scopes.clearScope(order);for(var entry:amounts.entrySet())scopes.insertScope(order,entry.getKey(),entry.getValue());
        var event=new LinkedHashMap<String,Object>();event.put("order",order);event.put("station",station);event.put("operator",AuthContext.getUserId());event.put("actor",actor);event.put("reason",reason);event.put("key",key);event.put("digest",digest);event.put("version",dto.getExpectedVersion());
        event.put("scopeJson",json.writeValueAsString(amounts.entrySet().stream().map(e->Map.of("productId",e.getKey(),"amount",e.getValue())).toList()));
        if(scopes.appendScope(event)!=1)throw new BusinessException("范围核对凭据未保存");
        return ExceptionActionEvidence.receipt(cases.action(order,actor,key));
    }
    public static String key(String value) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw new BusinessException("请保留有效的原办理编号重试");return value;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
