package com.example.aquaflow.service;

import com.example.aquaflow.entity.BarrelRecordLot;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.CustomerBarrelLot;
import com.example.aquaflow.exception.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 桶资产调整的纯校验与原批次汇总；数据库读取、锁顺序和写入仍由桶总账负责。 */
final class BarrelAdjustmentChecks {
    private final List<BarrelRecordLot> orderedProof;
    private final Set<Long> seen = new HashSet<>();
    private final BarrelLedgerService.LotConsumption result = new BarrelLedgerService.LotConsumption();
    private long total;
    private BigDecimal amount = BigDecimal.ZERO;

    BarrelAdjustmentChecks(List<BarrelRecordLot> proof) {
        orderedProof = new ArrayList<>(proof);
        orderedProof.sort(Comparator.comparing(BarrelRecordLot::getLotId, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    static void requireProof(int quantity, List<BarrelRecordLot> proof) {
        if (quantity <= 0 || proof == null || proof.isEmpty()) {
            throw new BusinessException("原调整缺少完整批次凭据，请核对原单及资产流水");
        }
    }

    List<BarrelRecordLot> orderedProof() {
        return orderedProof;
    }

    void requireProofLine(BarrelRecordLot line) {
        if (line.getLotId() == null || !seen.add(line.getLotId()) || line.getQty() == null
                || line.getQty() <= 0 || line.getUnitPrice() == null || line.getUnitPrice().signum() < 0
                || line.getAmount() == null || line.getAmount().compareTo(
                line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQty()))) != 0) {
            throw new BusinessException("原批次数量或金额凭据不完整，请核对原单及资产流水");
        }
    }

    void requireSourceLot(Long customer, Long station, Long product, BarrelRecordLot line, CustomerBarrelLot lot) {
        if (lot == null || !Objects.equals(customer, lot.getCustomerId()) || !Objects.equals(station, lot.getStationId())
                || !Objects.equals(product, lot.getProductId()) || lot.getUnitPrice() == null
                || lot.getUnitPrice().compareTo(line.getUnitPrice()) != 0 || lot.getQty() == null
                || lot.getRemainQty() == null || lot.getRemainQty() < 0 || lot.getRemainQty() > lot.getQty()
                || !(Integer.valueOf(1).equals(lot.getStatus()) || Integer.valueOf(2).equals(lot.getStatus()))
                || (Integer.valueOf(2).equals(lot.getStatus()) && lot.getRemainQty() != 0)) {
            throw new BusinessException("原批次归属、单价或状态不一致，请核对原单及资产流水");
        }
    }

    private void requireAvailableQuantity(BarrelRecordLot line, CustomerBarrelLot lot, int held, boolean restore) {
        if (held < 0 || held > lot.getRemainQty()) throw new BusinessException("原批次占用与余额不一致，请核对退还申请");
        if (restore) {
            if ((long) lot.getRemainQty() + line.getQty() > lot.getQty()) {
                throw new BusinessException("恢复数量超过原批次上限，请核对是否已有补回记录");
            }
        } else {
            if (!Integer.valueOf(1).equals(lot.getStatus()) || lot.getRemainQty() < line.getQty()) {
                throw new BusinessException("原批次已被使用，不能用其他批次代替撤销，请核对资产流水");
            }
            if (lot.getRemainQty() - held < line.getQty()) throw new BusinessException("原批次已被退还申请占用，请先核对原申请");
        }
    }

    void acceptAvailableLot(BarrelRecordLot line, CustomerBarrelLot lot, int held, boolean restore) {
        requireAvailableQuantity(line, lot, held, restore);
        total += line.getQty();
        amount = amount.add(line.getAmount());
        result.getDetails().add(new BarrelLedgerService.LotConsumption.Detail(
                lot.getId(), line.getQty(), line.getUnitPrice(), line.getAmount()));
        result.setHasMigratedPrice(result.isHasMigratedPrice() || Integer.valueOf(1).equals(lot.getIsMigrated()));
    }

    void requireTotalQuantity(int quantity) {
        if (total != quantity) throw new BusinessException("原调整数量与批次凭据不一致，请核对原单");
    }

    static void requirePreviewPhysicalBalance(int rights, int over, int quantity) {
        if ((long) rights + over - quantity < 0) {
            throw new BusinessException("撤销后客户实物桶数会为负，请核对交桶记录");
        }
    }

    static void requireManualPhysicalBalance(int right, int over, int qty) {
        if (qty <= 0 || (long) right + over - qty < 0) {
            throw new BusinessException("撤销后客户实物桶数不能为负；未领桶退款请提交退押金申请");
        }
    }

    static void requireManualAvailableRights(int right, int busy, long legacy, int qty) {
        if (qty > (long) right - busy - legacy) {
            throw new BusinessException("该部分桶数仍被订单或退还申请占用，请先处理原单");
        }
    }

    private void requireAsset(CustomerBarrelAsset asset, int quantity, boolean restore) {
        if (asset == null || asset.getQuantity() == null || asset.getRightAmount() == null
                || asset.getQuantity() < 0 || asset.getRightAmount().signum() < 0
                || (restore && ((long) asset.getQuantity() + quantity > Integer.MAX_VALUE
                || asset.getRightAmount().add(amount).compareTo(new BigDecimal("99999999.99")) > 0))
                || (!restore && (asset.getQuantity() < quantity || asset.getRightAmount().compareTo(amount) < 0))) {
            throw new BusinessException("原批次与拥有桶数不一致，请核对资产流水");
        }
    }

    BarrelLedgerService.LotConsumption resultForAsset(CustomerBarrelAsset asset, int quantity, boolean restore) {
        requireAsset(asset, quantity, restore);
        result.setAmount(amount);
        return result;
    }

    static void requireOverAdjustment(Long customerId, Long stationId, Long productId, int delta) {
        if (delta == 0) throw new BusinessException("欠桶调整量不能为 0");
        if (customerId == null || stationId == null || productId == null) {
            throw new BusinessException("欠桶调整必须指定客户、水站与商品");
        }
    }

    static void requireOverPhysicalBalance(int right, int before, int after) {
        if (right + after < 0) {
            throw new BusinessException("本次扣减超过实际在手桶数（当前 " + (right + before) + " 个），请核对交桶记录");
        }
    }
}
