package com.example.aquaflow.service;

import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.dto.HistoricalAssetClaimDTO;
import com.example.aquaflow.entity.HistoricalAssetClaim;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.ProductImageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 一次性期初认领。已提交清单不可由审核者改写；核对不一致则驳回重申请或沿既有调整订正。
 * 审核 CAS、建绑定与各账本写入共用一个事务，不产生订单、付款流水或渠道交易。
 */
@Service
public class HistoricalAssetClaimService {
    private final HistoricalAssetClaimMapper claims;
    private final CustomerStationConfigMapper bindings;
    private final StationMapper stations;
    private final ProductMapper products;
    private final InventoryMapper inventories;
    private final BarrelLedgerService barrels;
    private final StationAdjustmentService adjustments;
    private final ObjectMapper json=new ObjectMapper();
    private final Validator validator;
    private final ProductImageResolver images;

    public HistoricalAssetClaimService(HistoricalAssetClaimMapper claims,CustomerStationConfigMapper bindings,
        StationMapper stations,ProductMapper products,InventoryMapper inventories,BarrelLedgerService barrels,
        StationAdjustmentService adjustments,Validator validator,ProductImageResolver images) {
        this.claims=claims;this.bindings=bindings;this.stations=stations;this.products=products;
        this.inventories=inventories;this.barrels=barrels;this.adjustments=adjustments;this.validator=validator;this.images=images;
    }

    @Transactional(rollbackFor=Exception.class)
    public HistoricalAssetClaim submit(HistoricalAssetClaimDTO request) {
        Long customer=AuthContext.requireCustomerId();
        validate(request);
        var canonical=canonical(request);
        String payload=write(canonical), digest=digest(payload);
        lockCustomer(customer);
        var existing=claims.byToken(customer,canonical.clientToken());
        if(existing!=null) {
            if(!digest.equals(existing.getRequestDigest())) throw new BusinessException("同一申请凭据不能用于不同清单");
            return existing; // 原请求恢复不受后续商品/水站配置变化影响。
        }
        if(stations.getById(canonical.stationId())==null) throw new BusinessException("水站不存在");
        validateProducts(canonical);
        if(claims.active(customer,canonical.stationId())!=0) throw new BusinessException("本站已有待核对或已生效认领，请查看原申请");
        var claim=new HistoricalAssetClaim();
        claim.setCustomerId(customer);claim.setStationId(canonical.stationId());claim.setClientToken(canonical.clientToken());
        claim.setRequestDigest(digest);claim.setRequestJson(payload);
        if(claims.insert(claim)!=1) throw new BusinessException("申请未保存，请用原凭据重试");
        return claims.get(claim.getId());
    }

    public HistoricalAssetClaim mine(Long id) {
        Long customer=AuthContext.requireCustomerId();var claim=claims.get(id);
        if(claim==null||!customer.equals(claim.getCustomerId())) throw new BusinessException("申请不存在或无权访问");
        return claim;
    }
    public List<HistoricalAssetClaim> listMine(Long station) { return claims.mine(AuthContext.requireCustomerId(),station); }
    public List<HistoricalAssetClaimDTO.ProductOption> catalog(Long station) {
        AuthContext.requireCustomerId();
        if(station==null||stations.getById(station)==null) throw new BusinessException("水站不存在");
        return claims.catalog(station).stream().map(p -> new HistoricalAssetClaimDTO.ProductOption(
            p.id(),p.name(),p.spec(),p.category(),null,images.resolve(p.imageObjectName()))).toList();
    }
    public List<HistoricalAssetClaim> pending() { return claims.pending(managerStation()); }
    public HistoricalAssetClaim managerDetail(Long id) {
        Long station=managerStation();var claim=claims.get(id);
        if(claim==null||!station.equals(claim.getStationId())) throw new BusinessException("申请不存在或无权访问");
        return claim;
    }

    @Transactional(rollbackFor=Exception.class)
    public HistoricalAssetClaim review(Long id,HistoricalAssetClaimDTO.Review request) {
        Long station=managerStation();validate(request);
        String note=request.note()==null?null:request.note().trim();
        if(!request.approve()&&(note==null||note.isEmpty())) throw new BusinessException("驳回请说明核对差异");
        var observed=claims.get(id);
        if(observed==null||!station.equals(observed.getStationId())) throw new BusinessException("申请不存在或无权访问");
        lockCustomer(observed.getCustomerId());
        var claim=claims.lock(id);
        String target=request.approve()?"APPROVED":"REJECTED";
        if(!"PENDING".equals(claim.getStatus())) {
            if(target.equals(claim.getStatus())&&Objects.equals(note,claim.getReviewNote())) return claim;
            throw new BusinessException("申请已处理，请查看原结果");
        }
        if(request.approve()) {
            var snapshot=read(claim.getRequestJson());validateProducts(snapshot);
            // 不扩展通用资产调整的资格。只在本次核对事务中建关系；失败时关系也回滚。
            bindings.ensureExists(claim.getCustomerId(),station);
            for(var item:snapshot.items()) barrels.lockRights(claim.getCustomerId(),station,item.productId());
            requireEmptyOpeningLedger(claim.getCustomerId(),station);
            if(claims.decide(id,target,AuthContext.getUserId(),note)!=1) throw new BusinessException("申请已被处理");
            postOpening(claim,snapshot);
        } else if(claims.decide(id,target,AuthContext.getUserId(),note)!=1) throw new BusinessException("申请已被处理");
        return claims.get(id);
    }

    private void postOpening(HistoricalAssetClaim claim,HistoricalAssetClaimDTO snapshot) {
        for(var item:snapshot.items()) {
            int e=item.rights().intValueExact(),h=item.held().intValueExact(),tickets=item.tickets().intValueExact();
            if(e>0) post(claim,AdjustType.BARREL_GRANT,item.productId(),e,null,item.unitPrice());
            if(h!=e) post(claim,AdjustType.OVER_ADJUST,item.productId(),h-e,null,null);
            if(tickets>0) post(claim,AdjustType.TICKET_GRANT,item.productId(),tickets,null,null);
        }
        if(snapshot.depositBalance().signum()>0) post(claim,AdjustType.DEPOSIT_GRANT,null,null,snapshot.depositBalance(),null);
    }
    private void post(HistoricalAssetClaim claim,String type,Long product,Integer qty,BigDecimal amount,BigDecimal price) {
        // 服务端唯一凭据；真实历史押金走 MANUAL_GRANT，不当成新实收或虚构线上原款。
        String token="CLAIM-"+claim.getId()+"-"+type+"-"+(product==null?0:product);
        var adjustment=adjustments.create(claim.getCustomerId(),type,product,qty,amount,price,
            "历史资产认领 #"+claim.getId(),null,token);
        adjustments.execute(adjustment.getId());
    }

    private void requireEmptyOpeningLedger(Long customer,Long station) {
        boolean conflict=!claims.barrelHistory(customer,station).isEmpty()
            ||!claims.ticketHistory(customer,station).isEmpty()||!claims.depositHistory(customer,station).isEmpty()
            ||!claims.pendingPayments(customer,station).isEmpty()||!claims.reservations(customer,station).isEmpty()
            ||claims.barrelBalances(customer,station).stream().anyMatch(v->v!=null&&v!=0)
            ||claims.overBalances(customer,station).stream().anyMatch(v->v!=null&&v!=0)
            ||claims.ticketBalances(customer,station).stream().anyMatch(v->v!=null&&v!=0)
            ||claims.depositBalances(customer,station).stream().anyMatch(v->v!=null&&v.signum()!=0);
        if(conflict) throw new BusinessException("本站已有资产账或办理中业务，请核对后使用现有资产调整，不能重复迁入期初账");
    }
    private void validateProducts(HistoricalAssetClaimDTO request) {
        for(var item:request.items()) {
            var product=products.getById(item.productId());
            if(product==null||inventories.getByStationAndProduct(request.stationId(),item.productId())==null
                ||(product.getOwnerStationId()!=null&&!request.stationId().equals(product.getOwnerStationId())))
                throw new BusinessException("认领商品不属于本站");
            if((item.rights().signum()>0||item.held().signum()>0)&&!BarrelScope.isBarrel(product))
                throw new BusinessException("非桶装商品不能认领桶权益或实物桶");
        }
    }
    private HistoricalAssetClaimDTO canonical(HistoricalAssetClaimDTO r) {
        Set<Long> seen=new HashSet<>();boolean nonempty=r.depositBalance().signum()>0;
        List<HistoricalAssetClaimDTO.Item> items=new ArrayList<>();
        for(var item:r.items()) {
            if(!seen.add(item.productId())) throw new BusinessException("同一商品只能填写一行");
            int e=quantity(item.rights()),h=quantity(item.held()),t=quantity(item.tickets());
            nonempty|=e>0||h>0||t>0;
            if(item.unitPrice()!=null&&item.unitPrice().multiply(BigDecimal.valueOf(e)).compareTo(new BigDecimal("99999999.99"))>0)
                throw new BusinessException("权益快照金额超出可保存范围");
            items.add(new HistoricalAssetClaimDTO.Item(item.productId(),BigDecimal.valueOf(e),BigDecimal.valueOf(h),BigDecimal.valueOf(t),
                item.unitPrice()==null?null:item.unitPrice().setScale(2)));
        }
        if(!nonempty) throw new BusinessException("请填写需要核对的历史资产");
        items.sort(Comparator.comparing(HistoricalAssetClaimDTO.Item::productId));
        return new HistoricalAssetClaimDTO(r.stationId(),r.clientToken(),true,r.depositBalance().setScale(2),r.note().trim(),List.copyOf(items));
    }
    private int quantity(BigDecimal value) { try{return value.intValueExact();}catch(ArithmeticException e){throw new BusinessException("数量须为可保存的非负整数");} }
    private void validate(Object value) {
        if(value==null||!validator.validate(value).isEmpty()) throw new BusinessException("请完整确认认领清单，金额按分、数量按整数填写");
    }
    private void lockCustomer(Long customer) { if(claims.lockCustomer(customer)==null) throw new BusinessException("客户不存在"); }
    private Long managerStation() {
        if(!"STATION_MANAGER".equals(AuthContext.requireStaffRole())) throw new BusinessException("仅站长可核对认领");
        return AuthContext.requireStationId();
    }
    private String write(HistoricalAssetClaimDTO value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("认领快照写入失败",e);} }
    private HistoricalAssetClaimDTO read(String value) { try{return json.readValue(value,HistoricalAssetClaimDTO.class);}catch(Exception e){throw new IllegalStateException("认领快照读取失败",e);} }
    private String digest(String payload) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
