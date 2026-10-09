package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.dto.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 申请制退还编排；审批不代表收桶，收桶不代表已经交付退款。 */
@Service
public class ApprovedBarrelReturnService {
    @Autowired private OrderBarrelPurchaseService orderPurchases;
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BarrelReturnDetailMapper detailMapper;
    @Autowired private BarrelReturnArrangementMapper arrangementMapper;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    @Autowired private BarrelRecordMapper recordMapper;
    @Autowired private BarrelRecordLotMapper recordLotMapper;
    @Autowired private BarrelBusinessMapper businessMapper;
    @Autowired private BarrelLedgerService ledger;
    @Autowired private CustomerRiskService risk;
    @Autowired private ProductMapper productMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private StaffMapper staffMapper;
    @Autowired private PaymentRecordMapper paymentMapper;
    @Autowired private ConsumptionRefundMapper paymentLocks;
    @Autowired private DepositRecordService depositService;
    @Value("${app.payment.mock-wechat-pay:false}") private boolean mockWechatPay;

    public BarrelReturnDetail detail(Long recordId) { return policy.hasSchema()?detailMapper.get(recordId):null; }
    @Autowired private CustomerBarrelLotMapper refundLots;
    @Autowired private CustomerDepositAccountMapper refundAccounts;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 员工端在实际交款前只读核对原款；不创建占用、不调用任何退款写入口。
     * 2026-10-07：原先先提示交现金再由写接口判渠道，会诱导错误交款；资格仍须在写时重新校验。 */
    public Map<String,Object> refundEligibility(Long id, Long stationId) {
        RefundEligibilityRead read = new RefundEligibilityRead();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        boolean joinedTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transaction.execute(status -> readRefundEligibility(id, stationId, read));
        } catch (BusinessException e) {
            // 2026-10-08：嵌套预览失败会标记事务回滚；必须先退出事务，再转成不可办理提示。
            // 如果调用方已有事务，继续抛出，不能吞掉它已被标记回滚的事实。
            if (joinedTransaction || !read.legacyPreviewPending) throw e;
            return unavailable(read.view, e.getMessage());
        }
    }

    /** 每次读取独享上下文，仅历史批次试算拒绝可转成资格提示，归属等拒绝仍原样抛出。 */
    private static final class RefundEligibilityRead {
        private final Map<String,Object> view = new LinkedHashMap<>();
        private boolean legacyPreviewPending;
    }

    /** 由只读 TransactionTemplate 调用；本方法不捕获业务异常，失败必须先完成回滚。 */
    private Map<String,Object> readRefundEligibility(Long id, Long stationId, RefundEligibilityRead read) {
        BarrelRecord record = owned(id,stationId);
        BarrelReturnDetail detail = detail(id);
        Map<String,Object> view = read.view;
        view.put("recordId",id);
        view.put("legacy",detail==null);
        view.put("recordStatus",record.getStatus());
        view.put("detailStatus",detail==null?null:detail.getStatus());
        view.put("available",false);
        view.put("channel",null);
        view.put("refundAmount",record.getDepositRefund());
        Product product = record.getProductId()==null?null:productMapper.getById(record.getProductId());
        view.put("productName",product==null?null:product.getName());
        if (detail==null ? !Integer.valueOf(2).equals(record.getStatus()) : !"RECEIVED".equals(detail.getStatus()))
            return unavailable(view,"请先完成本申请的实际交接，再核对退款");
        if (record.getCustomerId()==null || record.getProductId()==null || record.getQuantity()==null || record.getQuantity()<=0)
            return unavailable(view,"申请信息不完整，请核实原申请");
        BigDecimal amount = BigDecimal.ZERO;
        String channel = BarrelRefundDTO.CHANNEL_CASH;
        boolean historicalSource = detail==null;
        if (detail==null) {
            // 复用实际核销算法的只读分支，必须同样扣掉其他用途和批次占用，不能另算FIFO金额。
            read.legacyPreviewPending = true;
            amount=ledger.previewRefundLots(record.getCustomerId(),stationId,record.getProductId(),record.getQuantity()).getAmount();
            read.legacyPreviewPending = false;
            if (ledger.overQty(record.getCustomerId(),stationId,record.getProductId())>0)
                return unavailable(view,"该商品仍有欠桶，请先核实归还记录");
        } else {
            Map<Long,CustomerBarrelLot> byId = new HashMap<>();
            for (CustomerBarrelLot lot : refundLots.listAvailable(record.getCustomerId(),stationId,record.getProductId())) byId.put(lot.getId(),lot);
            Set<String> channels = new HashSet<>();
            List<Map<String,Object>> heldLots=detailMapper.heldLots(id);
            if (heldLots.isEmpty()) return unavailable(view,"未找到本申请的退款批次，请核实原款");
            int quantity=0;
            for (Map<String,Object> held : heldLots) {
                Long lotId=((Number)held.get("lotId")).longValue();
                int qty=((Number)held.get("qty")).intValue();
                CustomerBarrelLot lot=byId.get(lotId);
                if (lot==null || qty<=0 || lot.getRemainQty()<qty)
                    return unavailable(view,"预留退款批次不足，请核实原申请");
                amount=amount.add((BigDecimal)held.get("amount")); quantity+=qty;
                BarrelRightPurchase purchase=businessMapper.purchaseByLot(lotId);
                OrderBarrelPurchase combined=orderPurchases.byLot(lotId);
                Long paymentId=purchase!=null?purchase.getPaymentId():combined!=null?combined.getPaymentId():null;
                if (purchase==null && combined==null) {
                    // 历史无线上凭据仍沿既有人工现金路径，不猜备注/单价来源。
                    historicalSource=true; channels.add(BarrelRefundDTO.CHANNEL_CASH); continue;
                }
                PaymentRecord original=paymentId==null?null:paymentMapper.getById(paymentId);
                if (original==null || original.getPaymentMethod()==null)
                    return unavailable(view,"原收款凭据未能核对，请核实后办理");
                if (!Objects.equals(original.getCustomerId(),record.getCustomerId()))
                    return unavailable(view,"原收款归属未能核对，请核实后办理");
                if (purchase!=null) {
                    if (combined!=null || !Objects.equals(purchase.getCustomerId(),record.getCustomerId())
                            || !Objects.equals(purchase.getStationId(),stationId) || !Objects.equals(purchase.getProductId(),record.getProductId())
                            || !Objects.equals(purchase.getLotId(),lotId) || !Objects.equals(original.getStationId(),stationId))
                        return unavailable(view,"独立押金原款关联未能核对，请核实后办理");
                } else {
                    Orders sourceOrder=combined.getOrderId()==null?null:orderMapper.getById(combined.getOrderId());
                    // 外派原款认结算站，押金资产认归属站；校验订单关联，不能强行要求原款stationId相同。
                    if (!Objects.equals(combined.getCustomerId(),record.getCustomerId()) || !Objects.equals(combined.getStationId(),stationId)
                            || !Objects.equals(combined.getProductId(),record.getProductId()) || !Objects.equals(combined.getLotId(),lotId)
                            || !Objects.equals(original.getOrderId(),combined.getOrderId()) || sourceOrder==null
                            || !Objects.equals(sourceOrder.getCustomerId(),record.getCustomerId()) || !Objects.equals(sourceOrder.getStationId(),stationId))
                        return unavailable(view,"随单押金原款关联未能核对，请核实后办理");
                }
                if (original.getPaymentMethod()==PayMethod.WECHAT) channels.add(BarrelRefundDTO.CHANNEL_ONLINE);
                else if (original.getPaymentMethod()==PayMethod.CASH) channels.add(BarrelRefundDTO.CHANNEL_CASH);
                else return unavailable(view,"原收款方式尚不能办理押金退款，请核实原款");
            }
            if (quantity!=record.getQuantity()) return unavailable(view,"退款批次数量与申请不一致，请核实原款");
            if (channels.size()!=1) return unavailable(view,"本申请包含不同原收款方式，请核实原申请");
            channel=channels.iterator().next();
        }
        view.put("refundAmount",amount);
        view.put("channel",channel);
        view.put("historicalSource",historicalSource);
        if (detail!=null || amount.signum()>0) {
            String level=risk.levelOf(record.getCustomerId(),stationId);
            if (CustomerRiskService.ALERT.equals(level) || CustomerRiskService.FREEZE.equals(level))
                return unavailable(view,risk.returnBlockedReason(record.getCustomerId(),stationId));
        }
        if (amount.signum()>0) {
            CustomerDepositAccount account=refundAccounts.getByCustomerAndStation(record.getCustomerId(),stationId);
            if (account==null || account.getBalance()==null || account.getBalance().compareTo(amount)<0)
                return unavailable(view,"押金账户余额不足，请核实原款和账目");
        }
        if (BarrelRefundDTO.CHANNEL_ONLINE.equals(channel) && !mockWechatPay)
            return unavailable(view,"线上原渠道退款尚不可用，本申请保留待退款责任，请勿改交现金");
        view.put("available",true);
        view.put("reason",historicalSource?"历史原款由归属站核实现金交付；办理时仍会核对金额和资格":"按原收款方式退还，办理时会再次核对资格");
        return view;
    }

    private Map<String,Object> unavailable(Map<String,Object> view,String reason) {
        view.put("reason",reason); return view;
    }

    public boolean isFeePayment(Long paymentId) {return policy.hasSchema() && detailMapper.byFeePayment(paymentId)!=null;}
    /** 收桶服务费退款独立留原款凭据，不退押金也不修改已经交接的事实。 */
    @Transactional
    public boolean refundFee(Long paymentId,String note) {
        BarrelReturnDetail found=policy.hasSchema()?detailMapper.byFeePayment(paymentId):null;
        if(found==null)return false;
        BarrelRecord record=owned(found.getRecordId(),AuthContext.requireStationId());
        ledger.lockRights(record.getCustomerId(),record.getStationId(),record.getProductId());
        requireDetail(record.getId());
        PaymentRecord original=paymentLocks.lockPayment(paymentId);
        if(original.getStatus()!=PaymentStatus.PAID)throw new BusinessException("这笔收桶服务费未收取或已退款");
        PaymentRecord refund=new PaymentRecord(); refund.setCustomerId(original.getCustomerId()); refund.setStationId(original.getStationId());
        refund.setAmount(original.getAmount().negate()); refund.setWaterAmount(BigDecimal.ZERO); refund.setBarrelDeposit(BigDecimal.ZERO);
        refund.setDeliveryFee(original.getAmount().negate()); refund.setPaymentMethod(original.getPaymentMethod()); refund.setStatus(PaymentStatus.REFUNDED);
        refund.setOperatorId(AuthContext.getUserId()); refund.setNote("收桶服务费原款退款，申请 "+record.getId());
        refund.setCreateTime(LocalDateTime.now()); refund.setUpdateTime(LocalDateTime.now()); paymentMapper.insert(refund);
        if(detailMapper.feeRefund(paymentId,refund.getId(),record.getId(),original.getAmount(),AuthContext.getUserId(),note)!=1
                || paymentMapper.updateStatusTo(paymentId,PaymentStatus.REFUNDED,PaymentStatus.PAID)!=1)throw new BusinessException("收桶费退款状态已变更，操作已回滚");
        return true;
    }
    private void cancelUnpaidFee(BarrelReturnDetail detail) {
        if(detail.getFeePaymentId()==null)return;
        PaymentRecord p=paymentLocks.lockPayment(detail.getFeePaymentId());
        if(p.getStatus()==PaymentStatus.REFUNDED || p.getStatus()==PaymentStatus.CANCELLED)return;
        if(paymentMapper.updateStatusTo(p.getId(),PaymentStatus.CANCELLED,PaymentStatus.PENDING)!=1)
            throw new BusinessException("已收取上门费，请先实际退还服务费再撤回或驳回申请");
    }

    @Transactional
    public void withdraw(Long id,Long customerId) {
        BarrelRecord record=recordMapper.getById(id);
        if (record==null || !Objects.equals(customerId,record.getCustomerId())) throw new BusinessException("无权撤回该申请");
        ledger.lockRights(customerId,record.getStationId(),record.getProductId());
        BarrelReturnDetail detail=requireDetail(id);
        if (!Set.of("APPLIED","APPROVED").contains(detail.getStatus())) throw new BusinessException("已经实际收桶的申请须继续办理退款，不能撤回");
        cancelUnpaidFee(detail);
        if (detailMapper.transition(id,detail.getStatus(),"WITHDRAWN")!=1 || recordMapper.reject(id,"客户撤回")!=1) throw new BusinessException("申请状态已变化");
        ledger.releaseRights("RETURN",id);
    }

    /** 顾客身份来自登录态；先占权益再固定批次，全部在同一事务。 */
    @Transactional
    public Map<String,Object> request(Long customerId, BarrelReturnRequestDTO dto) {
        if (dto.getStationId() == null || dto.getProductId() == null || dto.getQuantity() == null || dto.getQuantity() <= 0)
            throw new BusinessException("请先选择水站、商品及退还数量");
        String key = dto.getIdempotencyKey() == null ? "" : dto.getIdempotencyKey().trim();
        if (key.isEmpty() || key.length() > 64) throw new BusinessException("请提供退还申请凭据");
        String mode = dto.getPickupMode() == null ? "STORE" : dto.getPickupMode().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("STORE","PICKUP","COMBINED").contains(mode)) throw new BusinessException("请选择到店、上门或随订单收桶");
        BarrelReturnDetail old = detailMapper.byIntent(customerId,key);
        if (old != null) {
            BarrelRecord record = recordMapper.getById(old.getRecordId());
            if (!Objects.equals(record.getStationId(),dto.getStationId()) || !Objects.equals(record.getProductId(),dto.getProductId())
                    || !Objects.equals(record.getQuantity(),dto.getQuantity()) || !Objects.equals(old.getInitialPickupMode(),mode)
                    || !Objects.equals(old.getInitialCompanionOrderId(),dto.getCompanionOrderId()))
                throw new BusinessException("同一申请凭据不能用于不同内容");
            return result(record,old);
        }
        if (!BarrelScope.isBarrel(productMapper.getById(dto.getProductId()))) throw new BusinessException("该商品不涉及退桶");
        ledger.lockRights(customerId,dto.getStationId(),dto.getProductId());
        checkRisk(customerId,dto.getStationId());
        if (ledger.overQty(customerId,dto.getStationId(),dto.getProductId()) > 0)
            throw new BusinessException("请先归还该商品的欠桶，再申请退押金");
        if ("COMBINED".equals(mode)) {
            Orders companion=requireCompanion(customerId,dto.getStationId(),dto.getCompanionOrderId());
            if(companion.getStatus()!=OrderStatus.PENDING && companion.getStatus()!=OrderStatus.DELIVERING)
                throw new BusinessException("顺路收桶须选择仍在配送中的送水订单，已结束订单不能新建顺路收桶安排");
        }
        else if (dto.getCompanionOrderId() != null) throw new BusinessException("到店或独立上门不能关联送水订单");
        BarrelRecord record = new BarrelRecord();
        record.setCustomerId(customerId); record.setStationId(dto.getStationId()); record.setProductId(dto.getProductId());
        record.setQuantity(dto.getQuantity()); record.setType(2); record.setStatus(1);
        record.setDepositRefund(BigDecimal.ZERO); record.setNote(dto.getNote()); record.setCreateTime(LocalDateTime.now());
        recordMapper.insert(record);
        BarrelRightReservation reservation = ledger.reserveRights(customerId,dto.getStationId(),dto.getProductId(),
                dto.getQuantity(),"RETURN",record.getId());
        BarrelReturnDetail detail = new BarrelReturnDetail();
        detail.setRecordId(record.getId()); detail.setCustomerId(customerId); detail.setStationId(dto.getStationId());
        detail.setIdempotencyKey(key); detail.setPickupMode(mode); detail.setCompanionOrderId(dto.getCompanionOrderId());
        detail.setRequiredBarrels(dto.getQuantity()-reservation.getPickupQty()); detail.setNote(dto.getNote());
        try { detailMapper.insert(detail); }
        catch (DuplicateKeyException e) { throw new BusinessException("该申请已提交，请查看原申请"); }
        if (arrangementMapper.insert(detail)!=1) throw new BusinessException("原申请安排凭据未保存");
        BigDecimal amount = ledger.holdReturnLots(customerId,dto.getStationId(),dto.getProductId(),dto.getQuantity(),record.getId());
        Set<Integer> channels=new HashSet<>();
        for(Map<String,Object> held:detailMapper.heldLots(record.getId())) {
            BarrelRightPurchase purchase=businessMapper.purchaseByLot(((Number)held.get("lotId")).longValue());
            OrderBarrelPurchase combined=orderPurchases.byLot(((Number)held.get("lotId")).longValue());
            channels.add(purchase!=null?paymentMapper.getById(purchase.getPaymentId()).getPaymentMethod()
                    :combined!=null?paymentMapper.getById(combined.getPaymentId()).getPaymentMethod():PayMethod.CASH);
        }
        if(channels.size()>1)throw new BusinessException("这次退还跨越现金和微信押金批次，请减少数量、按渠道分次申请，再交桶");
        if (recordMapper.setPendingRefund(record.getId(),amount) != 1)
            throw new BusinessException("退桶金额快照未保存，请重新申请");
        record.setDepositRefund(amount);
        return result(record,detailMapper.get(record.getId()));
    }

    /** 归属站站长批准取桶安排；单独上门费独立收取，不从押金里暗扣。 */
    @Transactional
    public void approve(Long id, Long stationId, BarrelReturnApprovalDTO dto) {
        BarrelRecord record = owned(id,stationId);
        ledger.lockRights(record.getCustomerId(),stationId,record.getProductId());
        BarrelReturnDetail detail = requireDetail(id);
        if (!"APPLIED".equals(detail.getStatus())) throw new BusinessException("该申请已审批，请查看结果");
        requireArrangementVersion(detail,dto.getExpectedVersion());
        BigDecimal fee = dto.getPickupFee();
        if (fee == null || fee.signum()<0 || fee.compareTo(new BigDecimal("10000"))>0) throw new BusinessException("取桶费用不合法");
        if ((!"PICKUP".equals(detail.getPickupMode()) || detail.getRequiredBarrels()==0) && fee.signum()!=0)
            throw new BusinessException("到店、顺路或未领桶的权益退还不收独立上门费");
        if ("COMBINED".equals(detail.getPickupMode())) requireCompanion(record.getCustomerId(),stationId,detail.getCompanionOrderId());
        Long paymentId = null;
        if (fee.signum()>0) {
            PaymentRecord payment = new PaymentRecord();
            payment.setCustomerId(record.getCustomerId()); payment.setStationId(stationId);
            payment.setAmount(fee); payment.setWaterAmount(BigDecimal.ZERO); payment.setBarrelDeposit(BigDecimal.ZERO);
            payment.setDeliveryFee(fee);
            payment.setPaymentMethod(PayMethod.CASH); payment.setStatus(PaymentStatus.PENDING);
            payment.setNote("独立上门收桶费，申请 " + id); payment.setCreateTime(LocalDateTime.now()); payment.setUpdateTime(LocalDateTime.now());
            paymentMapper.insert(payment); paymentId = payment.getId();
        }
        if (detailMapper.approve(id,fee,paymentId,dto.getNote())!=1) throw new BusinessException("申请状态已变化");
    }

    /** 顾客确认应退金额及交接安排；这一确认不宣称资金已到账。 */
    @Transactional
    public void confirm(Long id, Long customerId, Integer expectedVersion) {
        BarrelRecord record = recordMapper.getById(id);
        if (record==null || !Objects.equals(customerId,record.getCustomerId())) throw new BusinessException("申请不属于当前客户");
        ledger.lockRights(customerId,record.getStationId(),record.getProductId());
        BarrelReturnDetail detail = requireDetail(id);
        if (!"APPROVED".equals(detail.getStatus())) throw new BusinessException("请等待水站批准取桶安排");
        requireArrangementVersion(detail,expectedVersion);
        if ("COMBINED".equals(detail.getPickupMode())) requireCompanion(customerId,record.getStationId(),detail.getCompanionOrderId());
        if (detail.getCustomerConfirmationCurrent()) return;
        // 联表更新同时保存确认时间与当前版本，MySQL可计为两张表各一行；0才是CAS未命中。
        int confirmedRows=detailMapper.confirmCustomer(id,detail.getArrangementVersion());
        if (confirmedRows<1 || confirmedRows>2) throw new BusinessException("申请状态已变化");
    }

    /** 客户本人明确选择安排，保留原申请和原款凭据；新增服务费仍由批准后另行授权。 */
    @Transactional
    public Map<String,Object> changeByCustomer(Long id,Long customerId,BarrelReturnArrangementDTO dto) {
        BarrelRecord record=recordMapper.getById(id);
        if(record==null || record.getType()!=2 || !Objects.equals(customerId,record.getCustomerId()))throw new BusinessException("申请不属于当前客户");
        return changeArrangement(record,dto,false,customerId);
    }

    /** 归属站站长只能提出新安排；原客户确认不继承到新版本。 */
    @Transactional
    public Map<String,Object> changeByStation(Long id,Long stationId,Long operatorId,BarrelReturnArrangementDTO dto) {
        return changeArrangement(owned(id,stationId),dto,true,operatorId);
    }

    private Map<String,Object> changeArrangement(BarrelRecord record,BarrelReturnArrangementDTO dto,boolean proposedByStation,Long actorId) {
        String mode=dto.getPickupMode(), key=dto.getIdempotencyKey()==null?"":dto.getIdempotencyKey().trim();
        String reason=dto.getReason()==null?"":dto.getReason().trim();
        if(!Set.of("STORE","PICKUP","COMBINED").contains(mode==null?"":mode) || dto.getExpectedVersion()==null || dto.getExpectedVersion()<1
                || key.isEmpty() || key.length()>64 || reason.isEmpty() || reason.length()>200)throw new BusinessException("请明确新方式、当前版本、变更原因和操作凭据");
        if(!"COMBINED".equals(mode) && dto.getCompanionOrderId()!=null)throw new BusinessException("到店或独立上门不能关联送水订单");
        String actor=(proposedByStation?"staff:":"customer:")+actorId;
        String digest=arrangementDigest(dto,reason);
        Map<String,Object> saved=arrangementMapper.replay(record.getId(),actor,key);
        if(saved!=null) {
            if(!Objects.equals(digest,saved.get("requestDigest")))throw new BusinessException("同一操作凭据不能用于不同安排");
            return result(record,detailMapper.get(record.getId()));
        }
        // 顺路单先锁订单再锁桶账，与订单取消一致；不能把瞬间已取消的单设成新安排。
        if("COMBINED".equals(mode)) {
            if(dto.getCompanionOrderId()==null || dto.getCompanionOrderId()<1)throw new BusinessException("请选择明确的送水订单");
            Orders order=orderMapper.getByIdForUpdate(dto.getCompanionOrderId());
            if(order==null || !Objects.equals(order.getCustomerId(),record.getCustomerId()) || !Objects.equals(order.getStationId(),record.getStationId())
                    || !OrderStatus.isCancellable(order.getStatus()))throw new BusinessException("请选择本客户、本水站仍在配送中的送水订单");
        }
        ledger.lockRights(record.getCustomerId(),record.getStationId(),record.getProductId());
        BarrelReturnDetail detail=requireDetail(record.getId());
        // 已持有申请锁后用当前读，不能在 RR 的旧快照里漏掉刚提交的同键操作。
        Map<String,Object> replay=arrangementMapper.replayForUpdate(record.getId(),actor,key);
        if(replay!=null) {
            if(!Objects.equals(digest,replay.get("requestDigest")))throw new BusinessException("同一操作凭据不能用于不同安排");
            return result(record,detail);
        }
        requireArrangementVersion(detail,dto.getExpectedVersion());
        if(!Set.of("APPLIED","APPROVED").contains(detail.getStatus()))throw new BusinessException("已经实际交接或结束的申请不能更改安排");
        if(Objects.equals(mode,detail.getPickupMode()) && Objects.equals(dto.getCompanionOrderId(),detail.getCompanionOrderId()))throw new BusinessException("安排未改变，请选择新方式或送水订单");
        // 已收取服务费只能先走既有真实退款；这里不代付、不退款、不覆盖旧流水。
        if(detail.getFeePaymentId()!=null && paymentLocks.lockPayment(detail.getFeePaymentId()).getStatus()==PaymentStatus.PAID)
            throw new BusinessException("原安排已收取服务费，请先由水站按原款实际退款，再变更安排");
        String before=arrangementSnapshot(detail);
        cancelUnpaidFee(detail);
        if(arrangementMapper.advance(record.getId(),dto.getExpectedVersion(),proposedByStation)!=1
                || detailMapper.changeArrangement(record.getId(),mode,dto.getCompanionOrderId())!=1)throw new BusinessException("安排已变化，请重新核对原申请");
        BarrelReturnDetail changed=requireDetail(record.getId());
        if(arrangementMapper.record(record.getId(),changed.getArrangementVersion(),actor,actorId,key,digest,reason,before,arrangementSnapshot(changed))!=1)
            throw new BusinessException("安排变更凭据未保存，本次变更已回滚");
        return result(record,detailMapper.get(record.getId()));
    }

    private void requireArrangementVersion(BarrelReturnDetail detail,Integer expected) {
        Integer actual=detail.getArrangementVersion();
        if(actual==null || (expected==null?actual!=1:!actual.equals(expected)))throw new BusinessException("安排已更新，请查看最新方式、费用后重新确认");
    }

    private String arrangementDigest(BarrelReturnArrangementDTO dto,String reason) {
        try {
            String body=json.writeValueAsString(Arrays.asList(dto.getPickupMode(),dto.getCompanionOrderId(),dto.getExpectedVersion(),reason));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(Exception e) {throw new IllegalStateException("无法保存安排操作凭据",e);}
    }

    private String arrangementSnapshot(BarrelReturnDetail detail) {
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("pickupMode",detail.getPickupMode());snapshot.put("companionOrderId",detail.getCompanionOrderId());snapshot.put("pickupFee",detail.getPickupFee());
        snapshot.put("feePaymentId",detail.getFeePaymentId());snapshot.put("status",detail.getStatus());snapshot.put("version",detail.getArrangementVersion());
        snapshot.put("requiresConfirmation",detail.getCustomerConfirmationRequired());snapshot.put("confirmedVersion",detail.getCustomerConfirmedVersion());
        snapshot.put("customerConfirmedTime",detail.getCustomerConfirmedTime()==null?null:detail.getCustomerConfirmedTime().toString());
        snapshot.put("approvedTime",detail.getApprovedTime()==null?null:detail.getApprovedTime().toString());
        try {return json.writeValueAsString(snapshot);} catch(tools.jackson.core.JacksonException e) {throw new IllegalStateException("无法保存安排变更快照",e);}
    }

    /** 老审批入口委托新申请流程，守住批准、收费授权和真实交接。 */
    @Transactional
    public void handle(Long id, Long stationId, Integer status, String note, Long operatorId,
                       String refundChannel, Long refundPaidBy) {
        BarrelRecord record = owned(id,stationId);
        if(Integer.valueOf(3).equals(status)) {
            List<OrderBarrelPurchase> originals=detailMapper.heldLots(id).stream()
                    .map(held -> orderPurchases.byLot(((Number)held.get("lotId")).longValue()))
                    .filter(Objects::nonNull).toList();
            orderPurchases.lockReturnSources(originals);
        }
        ledger.lockRights(record.getCustomerId(),stationId,record.getProductId());
        BarrelReturnDetail detail = requireDetail(id);
        if (Integer.valueOf(4).equals(status)) {
            if (!Set.of("APPLIED","APPROVED").contains(detail.getStatus()))
                throw new BusinessException("桶已经收到，须继续退款或处理交接争议，不能驳回抹去实物事实");
            cancelUnpaidFee(detail);
            if (detailMapper.transition(id,detail.getStatus(),"REJECTED")!=1 || recordMapper.reject(id,note)!=1)
                throw new BusinessException("申请状态已变化");
            ledger.releaseRights("RETURN",id); return;
        }
        if (Integer.valueOf(2).equals(status)) { receive(record,detail,operatorId,note); return; }
        if (!Integer.valueOf(3).equals(status) || !"RECEIVED".equals(detail.getStatus()))
            throw new BusinessException("请先完成批准、必要的费用授权和实际交接，再退押金");
        checkRisk(record.getCustomerId(),stationId);
        String channel = BarrelRefundDTO.normalizeChannel(refundChannel);
        if (channel==null) throw new BusinessException("请选择有效退款方式");
        if (BarrelRefundDTO.CHANNEL_ONLINE.equals(channel) && !mockWechatPay)
            throw new BusinessException("线上退款尚不可用，本申请仍保留待退款责任");
        Long payer = refundPaidBy==null ? operatorId : refundPaidBy;
        Staff staff = payer==null ? null : staffMapper.getById(payer);
        if (staff==null || !Objects.equals(staff.getStationId(),stationId)) throw new BusinessException("押金交付人须为归属站员工");
        for (Map<String,Object> held : detailMapper.heldLots(id)) {
            BarrelRightPurchase purchase = businessMapper.purchaseByLot(((Number)held.get("lotId")).longValue());
            OrderBarrelPurchase combined=orderPurchases.byLot(((Number)held.get("lotId")).longValue());
            if (purchase!=null || combined!=null) {
                PaymentRecord original = paymentMapper.getById(purchase!=null?purchase.getPaymentId():combined.getPaymentId());
                boolean online = original.getPaymentMethod()==PayMethod.WECHAT;
                if (online != BarrelRefundDTO.CHANNEL_ONLINE.equals(channel))
                    throw new BusinessException("该批押金须按原收款方式退回，请按不同渠道分别申请");
            } else if (BarrelRefundDTO.CHANNEL_ONLINE.equals(channel)) throw new BusinessException("历史押金没有线上原款凭据，请由归属站核实现金交付");
        }
        BarrelLedgerService.LotConsumption consumption = ledger.refundHeldRights(record.getCustomerId(),stationId,record.getProductId(),id);
        DepositRecord dr = new DepositRecord();
        dr.setCustomerId(record.getCustomerId()); dr.setProductId(record.getProductId()); dr.setQuantity(record.getQuantity());
        dr.setType(DepositType.RETURN_BARREL); dr.setAmount(consumption.getAmount()); dr.setOperatorId(operatorId);
        dr.setNote("按已确认交接退押金，申请 " + id);
        if (dr.getAmount().signum()>0) depositService.add(dr,stationId);
        for (BarrelLedgerService.LotConsumption.Detail d : consumption.getDetails()) {
            BarrelRecordLot row = new BarrelRecordLot(); row.setRecordId(id); row.setLotId(d.getLotId());
            row.setQty(d.getQty()); row.setUnitPrice(d.getUnitPrice()); row.setAmount(d.getAmount()); recordLotMapper.insert(row);
            BarrelRightPurchase purchase = businessMapper.purchaseByLot(d.getLotId());
            OrderBarrelPurchase combined=orderPurchases.byLot(d.getLotId());
            if(combined!=null)orderPurchases.refundReturned(combined,d.getQty(),d.getAmount(),id,payer);
            else if (purchase!=null) {
                PaymentRecord original = paymentMapper.getById(purchase.getPaymentId());
                PaymentRecord refund = new PaymentRecord(); refund.setCustomerId(record.getCustomerId()); refund.setStationId(stationId);
                refund.setAmount(d.getAmount().negate()); refund.setWaterAmount(BigDecimal.ZERO); refund.setBarrelDeposit(d.getAmount().negate());
                refund.setPaymentMethod(original.getPaymentMethod()); refund.setStatus(PaymentStatus.REFUNDED);
                refund.setOperatorId(payer); refund.setNote("独立押金按原渠道退还，申请 " + id);
                refund.setCreateTime(LocalDateTime.now()); refund.setUpdateTime(LocalDateTime.now()); paymentMapper.insert(refund);
                detailMapper.refundProof(refund.getId(),purchase.getId(),id,d.getAmount());
            }
        }
        if (detailMapper.transition(id,"RECEIVED","REFUNDED")!=1 || recordMapper.finishRefund(id,note,consumption.getAmount(),payer)!=1)
            throw new BusinessException("退款状态已变化，请核实原结果");
    }

    private void receive(BarrelRecord record, BarrelReturnDetail detail, Long operatorId, String note) {
        if (!"APPROVED".equals(detail.getStatus()))
            throw new BusinessException("请先批准退还申请");
        // 2026-10-08：免费原安排不卡收桶；新增费用/水站新安排保留授权，不伪造客户确认。
        if (detail.getCustomerConfirmationRequired() && !detail.getCustomerConfirmationCurrent())
            throw new BusinessException("请先由客户确认新增费用或水站提出的新安排");
        if ("COMBINED".equals(detail.getPickupMode())) requireCompanion(record.getCustomerId(),record.getStationId(),detail.getCompanionOrderId());
        if (detail.getFeePaymentId()!=null && paymentMapper.getById(detail.getFeePaymentId()).getStatus()!=PaymentStatus.PAID)
            throw new BusinessException("请先确认实际收到独立上门收桶费");
        int qty = detail.getRequiredBarrels();
        if (qty>0) {
            List<BarrelLedgerService.OverChange> changes = ledger.returnEmpty(record.getCustomerId(),record.getStationId(),
                    List.of(new BarrelLedgerService.ItemQty(record.getProductId(),qty)),operatorId);
            BarrelRecord received = new BarrelRecord(); received.setCustomerId(record.getCustomerId()); received.setStationId(record.getStationId());
            received.setProductId(record.getProductId()); received.setQuantity(qty); received.setType(7); received.setStatus(3);
            received.setClientToken("approved-return-"+record.getId()); received.setDepositRefund(BigDecimal.ZERO);
            received.setOverBefore(changes.get(0).getOverBefore()); received.setOverAfter(changes.get(0).getOverAfter());
            received.setOperatorId(operatorId); received.setNote("退还申请实际收桶 " + record.getId()); received.setCreateTime(LocalDateTime.now());
            recordMapper.insert(received);
        }
        if (detailMapper.receive(record.getId())!=1 || recordMapper.confirmReceived(record.getId(),operatorId,note)!=1)
            throw new BusinessException("交接状态已变化，请核实原结果");
    }

    private void checkRisk(Long customerId, Long stationId) {
        String level = risk.levelOf(customerId,stationId);
        if (CustomerRiskService.ALERT.equals(level) || CustomerRiskService.FREEZE.equals(level))
            throw new BusinessException(risk.returnBlockedReason(customerId,stationId));
    }
    private Orders requireCompanion(Long customerId,Long stationId,Long orderId) {
        Orders order = orderId==null ? null : orderMapper.getById(orderId);
        if (order==null || !Objects.equals(order.getCustomerId(),customerId) || !Objects.equals(order.getStationId(),stationId)
                || order.getStatus()==OrderStatus.CANCELLED)
            throw new BusinessException("顺路送水订单已取消，请在原退还申请中更改收桶方式或关联订单");
        return order;
    }
    private BarrelRecord owned(Long id,Long stationId) {
        BarrelRecord record = recordMapper.getById(id);
        if (record==null || record.getType()!=2 || !Objects.equals(record.getStationId(),stationId)) throw new BusinessException("无权处理该退还申请");
        return record;
    }
    private BarrelReturnDetail requireDetail(Long id) {
        BarrelReturnDetail detail = detailMapper.lock(id);
        if (detail==null) throw new BusinessException("该记录不属于新的申请制退还");
        return detail;
    }
    private Map<String,Object> result(BarrelRecord record,BarrelReturnDetail detail) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("recordId",record.getId());
        result.put("quantity",record.getQuantity()); result.put("refundAmount",record.getDepositRefund()); result.put("returnDetail",detail);
        return result;
    }
}
