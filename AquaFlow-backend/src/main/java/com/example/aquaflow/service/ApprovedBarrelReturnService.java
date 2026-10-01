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
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 申请制退还编排；审批不代表收桶，收桶不代表已经交付退款。 */
@Service
public class ApprovedBarrelReturnService {
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BarrelReturnDetailMapper detailMapper;
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
                    || !Objects.equals(record.getQuantity(),dto.getQuantity()) || !Objects.equals(old.getPickupMode(),mode)
                    || !Objects.equals(old.getCompanionOrderId(),dto.getCompanionOrderId()))
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
        BigDecimal amount = ledger.holdReturnLots(customerId,dto.getStationId(),dto.getProductId(),dto.getQuantity(),record.getId());
        Set<Integer> channels=new HashSet<>();
        for(Map<String,Object> held:detailMapper.heldLots(record.getId())) {
            BarrelRightPurchase purchase=businessMapper.purchaseByLot(((Number)held.get("lotId")).longValue());
            channels.add(purchase==null?PayMethod.CASH:paymentMapper.getById(purchase.getPaymentId()).getPaymentMethod());
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
    public void confirm(Long id, Long customerId) {
        BarrelRecord record = recordMapper.getById(id);
        if (record==null || !Objects.equals(customerId,record.getCustomerId())) throw new BusinessException("申请不属于当前客户");
        ledger.lockRights(customerId,record.getStationId(),record.getProductId());
        BarrelReturnDetail detail = requireDetail(id);
        if (!"APPROVED".equals(detail.getStatus())) throw new BusinessException("请等待水站批准取桶安排");
        if ("COMBINED".equals(detail.getPickupMode())) requireCompanion(customerId,record.getStationId(),detail.getCompanionOrderId());
        if (detail.getCustomerConfirmedTime()!=null) return;
        if (detailMapper.confirmCustomer(id)!=1) throw new BusinessException("申请状态已变化");
    }

    /** 新申请的老审批入口委托到这里，防止绕过批准和双确认。 */
    @Transactional
    public void handle(Long id, Long stationId, Integer status, String note, Long operatorId,
                       String refundChannel, Long refundPaidBy) {
        BarrelRecord record = owned(id,stationId);
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
            throw new BusinessException("请先完成批准、客户确认和实际交接，再退押金");
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
            if (purchase!=null) {
                PaymentRecord original = paymentMapper.getById(purchase.getPaymentId());
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
            if (purchase!=null) {
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
        if (!"APPROVED".equals(detail.getStatus()) || detail.getCustomerConfirmedTime()==null)
            throw new BusinessException("请先批准申请并由客户确认交接安排");
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
            throw new BusinessException("顺路送水订单已取消，请撤回申请后重新选择到店或上门安排");
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
