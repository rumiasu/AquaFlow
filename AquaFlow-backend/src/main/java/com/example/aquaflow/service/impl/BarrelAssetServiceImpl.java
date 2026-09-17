package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerBarrelAssetMapper;
import com.example.aquaflow.mapper.CustomerBarrelInTransitMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.BarrelAssetService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.DepositRecordService;
import com.example.aquaflow.util.PriceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 桶资产核心服务实现（站长人工补录 / 退桶）。
 *
 * <p><b>[2026-09-13 修复] 本类原先直写 `customer_barrel_asset.quantity` 与押金账户，
 * 绕过了桶账的唯一真相源 `customer_barrel_lot`。</b>后果是：站长给历史客户补 N 个桶后，
 * `asset.quantity = N` 但 `Σlot.remain_qty = 0` → `rightQty() = 0` → 客户申请退桶时
 * `consumeLots` 抛「退桶数(N)超过拥有的桶权益数(0)」→ <b>补录成功、却永远退不掉桶、也退不出押金</b>；
 * 同时恒等式「占用 = 权益 + over」永久破裂，对账再也平不了。</p>
 *
 * <p>现在两个方法都改为**经 {@link BarrelLedgerService} 写批次与汇总**，押金改为经
 * {@link DepositRecordService} 成对写（账户 + 流水），与本项目「每个数据只有一个写入口」的纪律一致。</p>
 */
@Service
@Slf4j
public class BarrelAssetServiceImpl implements BarrelAssetService {

    @Autowired
    private CustomerBarrelAssetMapper assetMapper;

    @Autowired
    private CustomerBarrelInTransitMapper inTransitMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    /** 桶账唯一写入口 */
    @Autowired
    private BarrelLedgerService barrelLedgerService;

    /** 押金账户 + 流水成对写 */
    @Autowired
    private DepositRecordService depositRecordService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purchaseBarrels(Long customerId, Long stationId,
                                List<BarrelPurchaseItem> items, Long operatorId) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (BarrelPurchaseItem item : items) {
            if (item.getQty() == null || item.getQty() <= 0) {
                continue;
            }

            Long productId = item.getProductId();
            if (productId == null) {
                throw new BusinessException("补录桶权益必须指定商品");
            }
            int qty = item.getQty();
            BigDecimal depositAmount = item.getDepositAmount() != null ? item.getDepositAmount() : BigDecimal.ZERO;

            // 单价来源：实收押金优先；未传（=纯资产调整）则回退到商品当前押金，并标记为「推断值」
            // [2026-09-16] 「商品当前押金」= 本站押金（inventory.deposit_price 优先），不是全局 product.deposit
            Product product = productMapper.getById(productId);
            boolean priceInferred = depositAmount.compareTo(BigDecimal.ZERO) <= 0;
            BigDecimal unitPrice = priceInferred
                    ? PriceUtil.calcDeposit(product, inventoryMapper.getByStationAndProduct(stationId, productId))
                    : depositAmount;

            // 1. 权益批次 = 唯一真相源。[DEF-8] 必须建 lot，否则 rightQty() 仍为 0，客户退不掉桶。
            //    createLot 内部同时同步 customer_barrel_asset 的数量与派生金额（right_amount）。
            barrelLedgerService.createLot(customerId, stationId, productId, unitPrice, qty, null, operatorId,
                    BarrelLedgerService.LotOrigin.manual(priceInferred, "站长人工补录桶权益"));

            // 2. 押金账户：金额与批次单价同源，成对写（账户 + 流水），不再直写余额
            if (depositAmount.compareTo(BigDecimal.ZERO) > 0) {
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setProductId(productId);
                dr.setType(DepositType.PURCHASE); // 1 新增押金桶（余额增加）
                dr.setAmount(depositAmount);      // 正值：方向由 type 决定
                dr.setUnitPrice(unitPrice);
                dr.setQuantity(qty);
                dr.setNote("站长人工补录押金（购桶入账）");
                dr.setOperatorId(operatorId);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordService.add(dr, stationId);
            }
        }

        log.info("[BarrelAsset] 补录桶权益完成: customerId={}, stationId={}, items={}", customerId, stationId, items.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnBarrels(Long customerId, Long stationId,
                              List<BarrelReturnItem> items, Long operatorId) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (BarrelReturnItem item : items) {
            if (item.getQty() == null || item.getQty() <= 0) {
                continue;
            }

            Long productId = item.getProductId();
            if (productId == null) {
                throw new BusinessException("退桶必须指定商品");
            }
            int qty = item.getQty();
            BigDecimal refundAmount = item.getRefundAmount() != null ? item.getRefundAmount() : BigDecimal.ZERO;

            // 1. FIFO 核销权益批次（行锁 + CAS，金额由批次单价决定 = 权威值），再同步汇总。
            //    [DEF-8] 旧实现只减 asset.quantity、lot 不动 → 批次可被重复退、金额算错。
            BarrelLedgerService.LotConsumption consumption =
                    barrelLedgerService.consumeLots(customerId, stationId, productId, qty, null, false);
            barrelLedgerService.decreaseRight(customerId, stationId, productId, qty, consumption.getAmount());

            // 2. 金额只认批次单价：调用方自报金额必须与核销结果一致，不一致直接拒绝（fail-closed）
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0
                    && refundAmount.compareTo(consumption.getAmount()) != 0) {
                throw new BusinessException("退款金额与批次核销金额不一致（批次合计 ¥"
                        + consumption.getAmount() + "，传入 ¥" + refundAmount + "），请刷新后重试");
            }

            // 3. 退押金：成对写（账户 + 流水），金额传正值，方向由 type 决定
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setProductId(productId);
                dr.setType(DepositType.RETURN_BARREL); // 6 退桶退押金（余额减少）
                dr.setAmount(refundAmount);
                dr.setQuantity(qty);
                dr.setNote("站长人工退桶退押金");
                dr.setOperatorId(operatorId);
                dr.setCreateTime(LocalDateTime.now());
                depositRecordService.add(dr, stationId);
            }
        }

        log.info("[BarrelAsset] 退桶出账完成: customerId={}, stationId={}, items={}", customerId, stationId, items.size());
    }

    @Override
    public List<CustomerBarrelAsset> getHeldAssets(Long customerId, Long stationId) {
        return assetMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    public List<BarrelInTransitDTO> getInTransitAssets(Long customerId, Long stationId) {
        List<CustomerBarrelInTransit> list = inTransitMapper.listByCustomerAndStation(customerId, stationId);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    private BarrelInTransitDTO toDTO(CustomerBarrelInTransit t) {
        BarrelInTransitDTO dto = new BarrelInTransitDTO();
        dto.setProductId(t.getProductId());
        dto.setQty(t.getQty());
        dto.setRelatedOrderId(t.getRelatedOrderId());
        dto.setStatus(t.getStatus());
        return dto;
    }
}