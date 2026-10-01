package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.dto.BarrelRightPurchaseDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 独立资产款的编排入口；桶账经总账、现金余额经押金服务，渠道结果经支付服务。 */
@Service
public class IndependentBarrelService {
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BarrelBusinessMapper mapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private StationMapper stationMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private CustomerStationConfigMapper stationConfigs;
    @Autowired private CustomerBarrelLotMapper lotMapper;
    @Autowired private PaymentRecordMapper paymentMapper;
    @Autowired private ConsumptionRefundMapper paymentLocks;
    @Autowired private BarrelLedgerService ledger;
    @Autowired private DepositRecordService depositService;
    @Autowired @Lazy private PaymentService paymentService;
    @org.springframework.beans.factory.annotation.Value("${app.payment.mock-wechat-pay:false}")
    private boolean mockWechatPay;

    /** 查询不创建权益；没有实际支付结果时仅返回待付款购买凭据。 */
    public List<BarrelRightPurchase> list(Long customerId, Long stationId) {
        return policy.hasSchema()?mapper.purchases(customerId, stationId):List.of();
    }

    public boolean isPurchasePayment(Long paymentId) { return policy.hasSchema() && mapper.purchaseByPayment(paymentId) != null; }
    @Transactional public void withdraw(Long id,Long customerId) {
        BarrelRightPurchase found=mapper.purchaseById(id);
        if(found==null || !Objects.equals(found.getCustomerId(),customerId))throw new BusinessException("购买凭据不属于当前客户");
        PaymentRecord payment=paymentLocks.lockPayment(found.getPaymentId());
        BarrelRightPurchase current=mapper.purchaseByPayment(found.getPaymentId());
        if("CANCELLED".equals(current.getStatus()))return;
        if(!"PENDING".equals(current.getStatus()) || payment.getStatus()!=PaymentStatus.PENDING)throw new BusinessException("押金已确认到账，请走权益退还申请");
        if(paymentMapper.updateStatusTo(payment.getId(),PaymentStatus.CANCELLED,PaymentStatus.PENDING)!=1 || mapper.cancelPurchase(id)!=1)
            throw new BusinessException("购买状态已变更，请查看原结果");
    }

    public Map<String,Object> quote(Long customerId, Long stationId, Long productId, int quantity) {
        if (!policy.isEnabled()) throw new BusinessException("暂未开放独立办理桶押金");
        if (quantity <= 0 || quantity > 1000) throw new BusinessException("办理数量须在1到1000之间");
        Station station=stationMapper.getById(stationId);
        if (station==null || !Integer.valueOf(1).equals(station.getStatus())) throw new BusinessException("该水站未营业，请先联系水站办理资产退还");
        Product product = productMapper.getById(productId);
        Inventory inventory = inventoryMapper.getByStationAndProduct(stationId, productId);
        if (!BarrelScope.isBarrel(product) || inventory == null || !Integer.valueOf(1).equals(product.getStatus())
                || !Integer.valueOf(1).equals(inventory.getEnabled())
                || product.getOwnerStationId()!=null && !Objects.equals(product.getOwnerStationId(),stationId))
            throw new BusinessException("本站暂不提供该桶装水，请选择其他商品");
        BigDecimal unitPrice = PriceUtil.calcDeposit(product, inventory);
        if (unitPrice == null || unitPrice.signum() <= 0) throw new BusinessException("本站尚未设置可办理的桶押金，请联系水站");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("unitPrice", unitPrice); result.put("amount", unitPrice.multiply(BigDecimal.valueOf(quantity)));
        result.put("quantity", quantity); result.put("productId", productId); result.put("stationId", stationId);
        result.put("productName", product.getName());
        result.put("stationName",station.getName());
        result.put("availableRights", ledger.availableRights(customerId, stationId, productId));
        result.put("notice", "仅办理桶押金，本次不送水和桶；收到押金后生效。首次领取对应桶无需还桶，已有欠桶优先补足。");
        result.put("onlineAvailable", mockWechatPay);
        return result;
    }

    /** 顾客购买身份由控制器取得；幂等重放在价格与上架校验之前，避免历史价格漂移。 */
    @Transactional
    public Map<String,Object> purchase(Long customerId, BarrelRightPurchaseDTO dto) {
        String key = dto.getIdempotencyKey() == null ? "" : dto.getIdempotencyKey().trim();
        if (key.isEmpty() || key.length() > 64) throw new BusinessException("请提供有效的购买凭据");
        BarrelRightPurchase existing = mapper.findPurchase(customerId, key);
        if (existing != null) {
            PaymentRecord payment = paymentMapper.getById(existing.getPaymentId());
            if (!Objects.equals(existing.getStationId(), dto.getStationId())
                    || !Objects.equals(existing.getProductId(), dto.getProductId())
                    || !Objects.equals(existing.getQuantity(), dto.getQuantity())
                    || !Objects.equals(payment.getPaymentMethod(), dto.getPaymentMethod()))
                throw new BusinessException("同一购买凭据不能用于不同内容");
            return result(existing, payment);
        }
        if (dto.getPaymentMethod() == null || (dto.getPaymentMethod() != PayMethod.WECHAT && dto.getPaymentMethod() != PayMethod.CASH))
            throw new BusinessException("桶押金不能使用水票支付");
        Map<String,Object> quoted = quote(customerId, dto.getStationId(), dto.getProductId(), dto.getQuantity());
        if (dto.getPaymentMethod() == PayMethod.WECHAT && !mockWechatPay)
            throw new BusinessException("线上支付暂不可用，请到归属水站办理并取得收款确认");
        PaymentRecord payment = new PaymentRecord();
        payment.setCustomerId(customerId); payment.setStationId(dto.getStationId());
        payment.setIdempotencyKey(paymentKey(key)); payment.setAmount((BigDecimal)quoted.get("amount"));
        payment.setWaterAmount(BigDecimal.ZERO); payment.setBarrelDeposit(payment.getAmount());
        payment.setPaymentMethod(dto.getPaymentMethod()); payment.setStatus(PaymentStatus.PENDING);
        payment.setNote("独立桶押金，收到款项才生效");
        payment.setCreateTime(LocalDateTime.now()); payment.setUpdateTime(LocalDateTime.now());
        try {
            paymentMapper.insert(payment);
            BarrelRightPurchase purchase = new BarrelRightPurchase();
            purchase.setCustomerId(customerId); purchase.setStationId(dto.getStationId());
            purchase.setProductId(dto.getProductId()); purchase.setQuantity(dto.getQuantity());
            purchase.setUnitPrice((BigDecimal)quoted.get("unitPrice")); purchase.setAmount(payment.getAmount());
            purchase.setPaymentId(payment.getId()); purchase.setIdempotencyKey(key);
            mapper.insertPurchase(purchase);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该购买已提交，请查看原购买结果");
        }
        payment = paymentService.confirmMockChannelIfApplicable(payment.getId());
        return result(mapper.findPurchase(customerId, key), payment);
    }

    /** 支付 CAS 成功后的唯一资产入账；同事务失败时款项、购买凭据和桶账一起回滚。 */
    @Transactional
    public void activate(PaymentRecord payment) {
        if (!policy.hasSchema()) return;
        BarrelRightPurchase purchase = mapper.purchaseByPayment(payment.getId());
        if (purchase == null) return;
        if (!Objects.equals(payment.getStationId(), purchase.getStationId())
                || payment.getAmount().compareTo(purchase.getAmount()) != 0)
            throw new BusinessException("桶押金收款与购买凭据不一致");
        if ("PAID".equals(purchase.getStatus())) return;
        if (!"PENDING".equals(purchase.getStatus()) || payment.getStatus() != PaymentStatus.PAID)
            throw new BusinessException("桶押金尚未实际收到");
        stationConfigs.ensureExists(purchase.getCustomerId(),purchase.getStationId());
        DepositRecord record = new DepositRecord();
        record.setCustomerId(purchase.getCustomerId()); record.setType(DepositType.PREPAID);
        record.setAmount(purchase.getAmount()); record.setOperatorId(AuthContext.getUserId());
        record.setProductId(purchase.getProductId()); record.setQuantity(purchase.getQuantity());
        record.setUnitPrice(purchase.getUnitPrice()); record.setNote("独立桶押金：购买凭据 " + purchase.getId());
        depositService.add(record, purchase.getStationId());
        CustomerBarrelLot lot = ledger.purchaseRight(purchase.getCustomerId(), purchase.getStationId(),
                purchase.getProductId(), purchase.getUnitPrice(), purchase.getQuantity(), AuthContext.getUserId());
        if (lotMapper.linkDepositRecord(lot.getId(), record.getId()) != 1)
            throw new BusinessException("押金批次凭据未关联，本次购买已回滚");
        if (mapper.activatePurchase(purchase.getId(), lot.getId()) != 1)
            throw new BusinessException("购买结果已变化，请刷新查询");
    }

    private Map<String,Object> result(BarrelRightPurchase purchase, PaymentRecord payment) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("purchase", purchase); result.put("paymentId", payment.getId());
        result.put("amount", payment.getAmount()); result.put("status", payment.getStatus());
        result.put("statusText", payment.getStatusText());
        return result;
    }

    private String paymentKey(String key) {
        try {
            return "BR:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8))).substring(0,60);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
