package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.constant.BarrelRecordType;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.BarrelRecordLot;
import com.example.aquaflow.entity.CustomerBarrelLot;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.StationAdjustment;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketLot;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.BarrelRecordLotMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationAdjustmentMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketHoldMapper;
import com.example.aquaflow.mapper.TicketLotMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.BarrelBalanceReader;
import com.example.aquaflow.service.DepositRecordService;
import com.example.aquaflow.service.StationAdjustmentService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.PriceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 站长资产调整单实现。
 *
 * <p>三个不变量：</p>
 * <ol>
 *   <li><b>唯一写入口</b>：桶类走 {@link BarrelLedgerService}，押金走 {@link DepositRecordService}，
 *       水票走 {@link TicketAccountService}；本类不直接改任何余额/数量列。</li>
 *   <li><b>一张单只生效一次</b>：执行是 CAS（PENDING→EFFECTIVE）并用 affected 判定，
 *       流水表还有 uk_*_adjustment 做数据库级兜底。</li>
 *   <li><b>不改历史</b>：撤销靠反向单，不 UPDATE/DELETE 已生效的数据。</li>
 * </ol>
 */
@Service
@Slf4j
public class StationAdjustmentServiceImpl implements StationAdjustmentService {

    @Autowired private StationAdjustmentMapper adjustmentMapper;
    @Autowired private BarrelLedgerService barrelLedgerService;
    @Autowired private BarrelBalanceReader barrelBalanceReader;
    @Autowired private DepositRecordService depositRecordService;
    @Autowired private TicketAccountService ticketAccountService;
    @Autowired private BarrelRecordMapper barrelRecordMapper;
    @Autowired private BarrelRecordLotMapper barrelRecordLotMapper;
    @Autowired private CustomerDepositAccountMapper depositAccountMapper;
    @Autowired private CustomerStationConfigMapper customerStationConfigMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TicketAccountMapper ticketAccountMapper;
    @Autowired private TicketHoldMapper ticketHoldMapper;
    @Autowired private TicketLotMapper ticketLotMapper;
    @Autowired private TicketRecordMapper ticketRecordMapper;

    /* ==================== 试算 ==================== */

    @Override
    public Map<String, Object> preview(Long customerId, String adjustType, Long productId,
                                       Integer qty, BigDecimal amount, BigDecimal unitPrice) {
        // 试算与创建共用本站绑定资格；先验权，再读取快照或试算批次。
        validateCustomer(customerId, requireMyStation());
        validateProduct(adjustType, productId, requireMyStation());
        Snapshot before = snapshot(customerId, adjustType, productId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adjustType", adjustType);
        out.put("adjustTypeText", AdjustType.textOf(adjustType));
        out.put("before", before.toMap());
        out.put("after", projectAfter(before, adjustType, qty, amount, unitPrice, productId).toMap());

        // 预估金额：桶撤销按批次实时试算（dryRun，不落库），其余直接给出传入值
        if (AdjustType.BARREL_REVOKE.equals(adjustType) && productId != null && qty != null && qty > 0) {
            BarrelLedgerService.LotConsumption c = barrelLedgerService.consumeLots(
                    customerId, requireMyStation(), productId, qty, null, true);
            out.put("estimatedRefund", c.getAmount());
            out.put("hasMigratedPrice", c.isHasMigratedPrice());
        } else if (unitPrice != null && qty != null && AdjustType.BARREL_GRANT.equals(adjustType)) {
            out.put("estimatedRightAmount", unitPrice.multiply(BigDecimal.valueOf(qty)));
        }
        return out;
    }

    /* ==================== 创建 ==================== */

    @Override
    public StationAdjustment create(Long customerId, String adjustType, Long productId,
                                    Integer qty, BigDecimal amount, BigDecimal unitPrice,
                                    String reason, String evidence, String clientToken) {
        return withDeadlockRetry(() -> createInternal(customerId, adjustType, productId, qty, amount, unitPrice,
                reason, evidence, clientToken, null));
    }

    private <T> T withDeadlockRetry(Supplier<T> command) {
        // 2026-10-08 实库反例：两个唯一索引及临时单号更新仍可能在 INSERT 阶段死锁。
        // 2026-11-08：执行阶段也会竞争缺失账户/批次的 gap lock；死锁及锁等待失败只整事务重试。
        // 必须等事务完整回滚后再用原凭据重试；已有外层事务时交给外层回滚，不在坏事务里继续。
        if (TransactionSynchronizationManager.isActualTransactionActive()) return command.get();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return new TransactionTemplate(transactionManager).execute(status -> command.get());
            } catch (PessimisticLockingFailureException conflict) {
                if (attempt == 4) throw new BusinessException("调整凭据正在处理，请使用原凭据重试");
            }
        }
        throw new IllegalStateException("unreachable adjustment retry state");
    }

    private StationAdjustment createInternal(Long customerId, String adjustType, Long productId,
                                             Integer qty, BigDecimal amount, BigDecimal unitPrice,
                                             String reason, String evidence, String clientToken, Long reverses) {
        Long stationId = requireMyStation();
        validateClientToken(clientToken);
        validateCustomer(customerId, stationId);
        validateFields(adjustType, productId, qty, amount, reason);
        validatePersistedMoney(amount, "金额");
        validatePersistedMoney(unitPrice, "单价");

        // 2026-10-08：旧实现全局命中就返回，既会泄露他站单据，也会把不同内容误当原请求。
        StationAdjustment existing = adjustmentMapper.findByClientToken(clientToken);
        if (existing != null) {
            requireSameRequest(existing, stationId, customerId, adjustType, productId, qty,
                    amount, unitPrice, reason, evidence, reverses);
            return existing;
        }
        validateProduct(adjustType, productId, stationId);

        StationAdjustment a = new StationAdjustment();
        a.setStationId(stationId);
        a.setCustomerId(customerId);
        a.setProductId(productId);
        a.setAdjustType(adjustType);
        a.setQty(qty);
        a.setAmount(amount);
        a.setUnitPrice(unitPrice);
        // 单价来源：调用方给了明确单价 → 视为可信；否则执行时回退商品当前押金 → 标记为推断值
        boolean inferred = (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0);
        a.setPriceSource(inferred ? 3 : 1);
        a.setIsMigrated(inferred ? 1 : 0);
        a.setReason(reason);
        a.setEvidence(evidence);
        a.setStatus("PENDING");
        a.setClientToken(clientToken);
        a.setOperatorId(AuthContext.getUserId());
        a.setReverses(reverses);
        a.setAdjustNo("TMP-" + Long.toHexString(System.nanoTime()));
        try {
            adjustmentMapper.insert(a);
        } catch (DuplicateKeyException conflict) {
            // 并发同键只允许回读同站、同内容、同用途的胜出单；没有命中则保留真实数据库错误。
            // 2026-10-08 实库反例：重复 INSERT 已持有共享锁，多个重试再升级排他锁会死锁。
            // 共享锁仍是当前读，可看到胜出单；这里只核内容并返回，不需要升级写锁。
            existing = adjustmentMapper.findByClientTokenForShare(clientToken);
            if (existing == null) throw conflict;
            requireSameRequest(existing, stationId, customerId, adjustType, productId, qty,
                    amount, unitPrice, reason, evidence, reverses);
            return existing;
        }

        String no = String.format("ADJ%s-%06d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")), a.getId());
        adjustmentMapper.setAdjustNo(a.getId(), no);
        a.setAdjustNo(no);
        return a;
    }

    /* ==================== 执行 ==================== */

    @Override
    public void execute(Long id) {
        // 外部独立执行由此开启/重试事务；认领、冲正已持有外层事务时失败必须向外透传。
        withDeadlockRetry(() -> { executeInternal(id); return null; });
    }

    private void executeInternal(Long id) {
        Long stationId = requireMyStation();
        StationAdjustment a = adjustmentMapper.getById(id);
        if (a == null || !stationId.equals(a.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        if (!"PENDING".equals(a.getStatus())) {
            throw new BusinessException("调整单当前状态不可执行：" + a.getStatus());
        }
        validateCustomer(a.getCustomerId(), stationId);
        validateProduct(a.getAdjustType(), a.getProductId(), stationId);

        // 2026-11-08：认领允许追加已有资产后，等待锁之前的 RR 快照可能已过期。
        // 执行记录必须当前读；页面 preview 仍走不加锁、不建账户的普通查询。
        Snapshot before = snapshot(a.getCustomerId(), a.getAdjustType(), a.getProductId(), true);
        Snapshot after = projectAfter(before, a.getAdjustType(), a.getQty(), a.getAmount(), a.getUnitPrice(), a.getProductId());

        // 2) CAS 占用执行权：affected=0 说明已被并发执行/撤销
        int claimed = adjustmentMapper.markEffectiveIf(id, "PENDING", "EFFECTIVE",
                AuthContext.getUserId(), before.toJson(), after.toJson());
        if (claimed == 0) {
            throw new BusinessException("调整单已被处理，请刷新后重试");
        }

        // 3) 按类型落库（各自唯一写入口）
        apply(a, stationId, before);

        auditLogService.log("ADJUST", "EXECUTE", "adjust:" + id,
                "type=" + a.getAdjustType() + ",qty=" + a.getQty() + ",amount=" + a.getAmount()
                        + ",before=" + before.toJson() + ",after=" + after.toJson(), null);
        log.info("[Adjust] 执行成功 id={}, no={}, type={}, qty={}, amount={}",
                id, a.getAdjustNo(), a.getAdjustType(), a.getQty(), a.getAmount());
    }

    private void apply(StationAdjustment a, Long stationId, Snapshot before) {
        String type = a.getAdjustType();
        Long customerId = a.getCustomerId();
        Long productId = a.getProductId();
        Long operatorId = AuthContext.getUserId();
        String note = "调整单 " + a.getAdjustNo() + "：" + a.getReason();

        if (a.getReverses() != null && (AdjustType.BARREL_GRANT.equals(type) || AdjustType.BARREL_REVOKE.equals(type))) {
            StationAdjustment source = adjustmentMapper.getByIdForUpdate(a.getReverses());
            if (source == null || !Objects.equals(stationId, source.getStationId())
                    || !Objects.equals(customerId, source.getCustomerId()) || !Objects.equals(productId, source.getProductId())
                    || !Objects.equals(a.getQty(), source.getQty()) || !Objects.equals(type, mirrorType(source.getAdjustType()))
                    || !"EFFECTIVE".equals(source.getStatus()) || source.getReversedBy() != null) {
                throw new BusinessException("原调整与反向单不一致，请核对原单");
            }
            boolean restore = AdjustType.BARREL_GRANT.equals(type);
            BarrelLedgerService.LotConsumption exact = barrelLedgerService.reverseAdjustmentLots(customerId, stationId,
                    productId, a.getQty(), sourceBarrelDetails(source), restore);
            Long record = writeBarrelRecord(a, stationId, restore ? BarrelRecordType.ADJUST_INCREASE : BarrelRecordType.ADJUST_DECREASE,
                    a.getQty(), before.over, before.over, note + "（按原调整批次冲正 ¥" + exact.getAmount() + "）");
            writeLotDetails(record, exact.getDetails());

        } else if (AdjustType.BARREL_GRANT.equals(type)) {
            BigDecimal price = resolveUnitPrice(a, productId);
            boolean inferred = (a.getUnitPrice() == null || a.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0);
            barrelLedgerService.lockRights(customerId, stationId, productId);
            CustomerBarrelLot lot = barrelLedgerService.createLot(customerId, stationId, productId, price, a.getQty(), null, operatorId,
                    BarrelLedgerService.LotOrigin.manual(inferred, note));
            Long record = writeBarrelRecord(a, stationId, BarrelRecordType.ADJUST_INCREASE, a.getQty(),
                    before.over, before.over, note);
            writeLotDetails(record, List.of(new BarrelLedgerService.LotConsumption.Detail(lot.getId(), a.getQty(),
                    lot.getUnitPrice(), lot.getUnitPrice().multiply(BigDecimal.valueOf(a.getQty())))));

        } else if (AdjustType.BARREL_REVOKE.equals(type)) {
            barrelLedgerService.requireManualRevokeSafe(customerId, stationId, productId, a.getQty());
            BarrelLedgerService.LotConsumption c = barrelLedgerService.consumeLots(
                    customerId, stationId, productId, a.getQty(), null, false);
            barrelLedgerService.decreaseRight(customerId, stationId, productId, a.getQty(), c.getAmount());
            Long record = writeBarrelRecord(a, stationId, BarrelRecordType.ADJUST_DECREASE, a.getQty(),
                    before.over, before.over,
                    note + "（按批次单价核销，合计 ¥" + c.getAmount()
                            + (c.isHasMigratedPrice() ? "，含历史推断单价批次" : "") + "）");
            writeLotDetails(record, c.getDetails());

        } else if (AdjustType.OVER_ADJUST.equals(type)) {
            BarrelLedgerService.OverChange ch = barrelLedgerService.adjustOver(
                    customerId, stationId, productId, a.getQty(), operatorId);
            writeBarrelRecord(a, stationId,
                    a.getQty() > 0 ? BarrelRecordType.ADJUST_INCREASE : BarrelRecordType.ADJUST_DECREASE,
                    Math.abs(a.getQty()), ch.getOverBefore(), ch.getOverAfter(), note);

        } else if (AdjustType.DEPOSIT_GRANT.equals(type) || AdjustType.DEPOSIT_DEDUCT.equals(type)) {
            boolean grant = AdjustType.DEPOSIT_GRANT.equals(type);
            DepositRecord dr = new DepositRecord();
            dr.setCustomerId(customerId);
            dr.setStationId(stationId);
            dr.setProductId(productId);
            // 9=人工补录押金（增加） / 4=人工调整（扣减）。方向由类型决定，金额一律传正值。
            dr.setType(grant ? DepositType.MANUAL_GRANT : DepositType.ADJUSTMENT);
            dr.setAmount(a.getAmount());
            dr.setNote(note);
            dr.setOperatorId(operatorId);
            dr.setAdjustmentId(a.getId());
            dr.setCreateTime(LocalDateTime.now());
            depositRecordService.add(dr, stationId);

        } else if (AdjustType.TICKET_GRANT.equals(type) || AdjustType.TICKET_DEDUCT.equals(type)) {
            int delta = AdjustType.TICKET_GRANT.equals(type) ? a.getQty() : -a.getQty();
            ticketAccountService.adjustTicket(customerId, productId, delta, stationId, a.getId());

        } else {
            throw new BusinessException("不支持的调整类型：" + type);
        }
    }

    /** 人工调整流水：type=6 增加 / type=9 减少；adjustment_id 是幂等与「属于哪张单」的唯一凭据 */
    private Long writeBarrelRecord(StationAdjustment a, Long stationId, int recordType, int qty,
                                   Integer overBefore, Integer overAfter, String note) {
        BarrelRecord r = new BarrelRecord();
        r.setCustomerId(a.getCustomerId());
        r.setStationId(stationId);
        r.setProductId(a.getProductId());
        r.setType(recordType);
        r.setQuantity(qty);
        r.setStatus(1);
        r.setNote(note.length() > 500 ? note.substring(0, 500) : note);
        r.setOperatorId(AuthContext.getUserId());
        r.setCreateTime(LocalDateTime.now());
        r.setOverBefore(overBefore);
        r.setOverAfter(overAfter);
        r.setDeliveredQty(0);
        r.setReturnedQty(0);
        r.setAdjustmentId(a.getId());
        barrelRecordMapper.insert(r);
        if (r.getId() == null) throw new BusinessException("调整流水未保存，本次调整未生效");
        return r.getId();
    }

    private void writeLotDetails(Long recordId, List<BarrelLedgerService.LotConsumption.Detail> details) {
        for (BarrelLedgerService.LotConsumption.Detail detail : details) {
            BarrelRecordLot row = new BarrelRecordLot();
            row.setRecordId(recordId); row.setLotId(detail.getLotId()); row.setQty(detail.getQty());
            row.setUnitPrice(detail.getUnitPrice()); row.setAmount(detail.getAmount());
            if (barrelRecordLotMapper.insert(row) != 1) throw new BusinessException("原批次凭据未保存，本次调整未生效");
        }
    }

    private List<BarrelRecordLot> sourceBarrelDetails(StationAdjustment source) {
        BarrelRecord record = barrelRecordMapper.getByAdjustmentId(source.getId());
        int expectedType = AdjustType.BARREL_GRANT.equals(source.getAdjustType())
                ? BarrelRecordType.ADJUST_INCREASE : BarrelRecordType.ADJUST_DECREASE;
        if (record == null || !Objects.equals(source.getId(), record.getAdjustmentId())
                || !Objects.equals(source.getCustomerId(), record.getCustomerId())
                || !Objects.equals(source.getStationId(), record.getStationId())
                || !Objects.equals(source.getProductId(), record.getProductId())
                || !Objects.equals(source.getQty(), record.getQuantity()) || !Integer.valueOf(expectedType).equals(record.getType())
                || !Integer.valueOf(1).equals(record.getStatus()) || record.getRelatedOrderId() != null) {
            throw new BusinessException("原调整缺少对应桶流水和批次凭据，请核对原单及资产流水");
        }
        List<BarrelRecordLot> details = barrelRecordLotMapper.listByRecordId(record.getId());
        if (details.isEmpty() || details.stream().anyMatch(d -> !Objects.equals(record.getId(), d.getRecordId()))) {
            throw new BusinessException("原调整缺少完整批次凭据，请核对原单及资产流水");
        }
        return details;
    }

    private BigDecimal resolveUnitPrice(StationAdjustment a, Long productId) {
        if (a.getUnitPrice() != null && a.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return a.getUnitPrice();
        }
        Product p = productMapper.getById(productId);
        // [2026-09-16] 缺单价时按**本站押金**推断（inventory.deposit_price 优先），与下单/补录同口径
        BigDecimal dep = PriceUtil.calcDeposit(p, inventoryMapper.getByStationAndProduct(a.getStationId(), productId));
        if (dep.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("补录桶权益必须能确定单价：请指定单价，或先为该商品设置押金");
        }
        return dep;
    }

    /* ==================== 撤销（反向单） ==================== */

    @Override
    public StationAdjustment reverse(Long id, String reason, String clientToken) {
        return withDeadlockRetry(() -> reverseInternal(id, reason, clientToken));
    }

    private StationAdjustment reverseInternal(Long id, String reason, String clientToken) {
        Long stationId = requireMyStation();
        validateClientToken(clientToken);
        // 2026-10-08：旧实现复用任意创建凭据再改 reverses，可冲错资金；先锁原单并固定冲正来源。
        StationAdjustment src = adjustmentMapper.getByIdForUpdate(id);
        if (src == null || !stationId.equals(src.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        String reverseReason = "撤销 " + src.getAdjustNo() + "：" + (reason == null ? "" : reason);
        StationAdjustment existing = adjustmentMapper.findByClientToken(clientToken);
        if (existing != null) {
            requireSameRequest(existing, stationId, src.getCustomerId(), mirrorType(src.getAdjustType()),
                    src.getProductId(), mirrorQty(src), src.getAmount(), src.getUnitPrice(),
                    reverseReason, null, src.getId());
            if ("REVERSED".equals(src.getStatus()) && Objects.equals(src.getReversedBy(), existing.getId())
                    && "EFFECTIVE".equals(existing.getStatus())) {
                return existing; // 已完成的同一冲正重试，不改流水、关联或状态。
            }
            throw new BusinessException("该凭据关联的冲正尚未完整生效，请核实原单");
        }
        if (!"EFFECTIVE".equals(src.getStatus()) || src.getReversedBy() != null) {
            throw new BusinessException("仅已生效的调整单可撤销，当前状态：" + src.getStatus());
        }
        requireExactReversal(src, true);

        StationAdjustment rev = createInternal(src.getCustomerId(), mirrorType(src.getAdjustType()),
                src.getProductId(), mirrorQty(src), src.getAmount(), src.getUnitPrice(),
                reverseReason, null, clientToken, src.getId());
        if (!"PENDING".equals(rev.getStatus())) {
            throw new BusinessException("冲正凭据状态已变化，请核实原单");
        }
        execute(rev.getId());

        int marked = adjustmentMapper.markReversedIf(src.getId(), "EFFECTIVE", "REVERSED", rev.getId());
        if (marked == 0) {
            throw new BusinessException("原单状态已变更，撤销失败");
        }
        auditLogService.log("ADJUST", "REVERSE", "adjust:" + id,
                "reversedBy=" + rev.getId() + ",reason=" + reason, null);
        rev.setStatus("EFFECTIVE");
        return rev;
    }

    /**
     * 镜像数量不等于冲回原资产：跨价 FIFO 会扣掉他批价值，当前价补入也无法恢复原款来源。
     * 2026-11-04：缺批次分摊凭据的反向必须先核实；已成功的幂等重放在调用本方法前返回。
     */
    private void requireExactReversal(StationAdjustment source, boolean locked) {
        if (AdjustType.BARREL_GRANT.equals(source.getAdjustType())
                || AdjustType.BARREL_REVOKE.equals(source.getAdjustType())) {
            // 真正写入时由 reverseAdjustmentLots 在统一锁下复核；展示和预检只读，不补历史凭据。
            barrelLedgerService.previewAdjustmentReversal(source.getCustomerId(), source.getStationId(), source.getProductId(),
                    source.getQty() == null ? 0 : source.getQty(), sourceBarrelDetails(source),
                    AdjustType.BARREL_REVOKE.equals(source.getAdjustType()));
            return;
        }
        if (AdjustType.TICKET_DEDUCT.equals(source.getAdjustType())) {
            throw new BusinessException("原扣票调整缺少可精确恢复的批次明细，不能自动撤销；请核对原单及资产流水");
        }
        if (!AdjustType.TICKET_GRANT.equals(source.getAdjustType())) return;

        Long customer = source.getCustomerId();
        Long station = source.getStationId();
        Long product = source.getProductId();
        String unavailable = "原补票批次已使用、被占用或不符合原批次撤销条件，请先核实原资产";
        // 和预留/扣票使用相同账户锁，当前读批次与预留；锁保留至反向单的 FIFO 扣减完成。
        if (source.getQty() == null || source.getQty() <= 0
                || (locked ? ticketAccountMapper.lockAccount(customer, station, product)
                    : ticketAccountMapper.getByCustomerProductStation(customer, product, station)) == null) {
            throw new BusinessException(unavailable);
        }
        Map<Long, Integer> held = new HashMap<>();
        for (Map<String, Object> row : (locked ? ticketHoldMapper.heldForAccount(customer, station, product)
                : ticketHoldMapper.heldForAccountReadOnly(customer, station, product))) {
            held.merge(((Number) row.get("lot_id")).longValue(),
                    ((Number) row.get("quantity")).intValue(), Integer::sum);
        }
        List<TicketLot> lots = locked ? ticketLotMapper.listUsableFifo(customer, station, product)
                : ticketLotMapper.listUsableFifoReadOnly(customer, station, product);
        List<TicketRecord> records = ticketRecordMapper.listByCustomerAndProduct(customer, product, station)
                .stream().filter(r -> Objects.equals(source.getId(), r.getAdjustmentId())).toList();
        if (records.size() != 1) throw new BusinessException(unavailable);
        TicketRecord original = records.get(0);
        TicketLot next = lots.stream().filter(l -> l.getRemainQty() != null
                && l.getRemainQty() > held.getOrDefault(l.getId(), 0)).findFirst().orElse(null);
        if (next == null || !Objects.equals(customer, original.getCustomerId())
                || !Objects.equals(station, original.getStationId()) || !Objects.equals(product, original.getProductId())
                || !Objects.equals(source.getQty(), original.getIncreaseQty())
                || !Integer.valueOf(0).equals(original.getDecreaseQty())
                || !Objects.equals(original.getTicketLotId(), next.getId())
                || !Objects.equals(source.getQty(), next.getQty())
                || !Objects.equals(source.getQty(), next.getRemainQty())
                || held.getOrDefault(next.getId(), 0) != 0
                || !Integer.valueOf(TicketLot.SourceType.MANUAL).equals(next.getSourceType())
                || original.getUnitPrice() == null || next.getUnitPrice() == null
                || original.getUnitPrice().compareTo(next.getUnitPrice()) != 0) {
            throw new BusinessException(unavailable);
        }
    }

    /** 保留既有响应字段，客户信息只能随本站调整单在服务层补齐。 */
    @Override
    public Map<String, Object> decorate(StationAdjustment a) {
        if (a == null || !Objects.equals(requireMyStation(), a.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("adjustNo", a.getAdjustNo());
        m.put("customerId", a.getCustomerId());
        var customer = customerMapper.getById(a.getCustomerId());
        m.put("customerName", customer == null ? null : customer.getName());
        m.put("customerPhone", customer == null ? null : customer.getPhone());
        m.put("productId", a.getProductId());
        // 2026-10-24：名称只辅助识别本站原调整单，不能借此展示他站私有商品。
        Product product = a.getProductId() == null ? null : productMapper.getById(a.getProductId());
        boolean visibleProduct = product != null && (product.getOwnerStationId() == null
                || Objects.equals(a.getStationId(), product.getOwnerStationId()));
        m.put("productName", visibleProduct ? product.getName() : null);
        m.put("productSpec", visibleProduct ? product.getSpec() : null);
        m.put("adjustType", a.getAdjustType());
        m.put("adjustTypeText", AdjustType.textOf(a.getAdjustType()));
        m.put("qty", a.getQty());
        m.put("amount", a.getAmount());
        m.put("unitPrice", a.getUnitPrice());
        m.put("priceSource", a.getPriceSource());
        m.put("isMigrated", a.getIsMigrated());
        m.put("reason", a.getReason());
        m.put("evidence", a.getEvidence());
        m.put("beforeSnapshot", a.getBeforeSnapshot());
        m.put("afterSnapshot", a.getAfterSnapshot());
        m.put("status", a.getStatus());
        m.put("statusText", statusTextOf(a.getStatus()));
        m.put("operatorId", a.getOperatorId());
        m.put("executorId", a.getExecutorId());
        m.put("reverses", a.getReverses());
        m.put("reversedBy", a.getReversedBy());
        m.put("executeTime", a.getExecuteTime());
        m.put("createTime", a.getCreateTime());
        String reverseUnavailableReason = reverseUnavailableReason(a);
        m.put("canReverse", reverseUnavailableReason == null);
        m.put("reverseUnavailableReason", reverseUnavailableReason);
        return m;
    }

    /** 只读能力提示不能代替执行时的加锁重校验，也不得创建账户、over 或任何凭据。 */
    private String reverseUnavailableReason(StationAdjustment source) {
        if (!"EFFECTIVE".equals(source.getStatus()) || source.getReversedBy() != null) {
            return "仅已生效且尚未撤销的调整单可以撤销";
        }
        try {
            validateCustomer(source.getCustomerId(), source.getStationId());
            validateProduct(source.getAdjustType(), source.getProductId(), source.getStationId());
            requireExactReversal(source, false);
            if (AdjustType.DEPOSIT_GRANT.equals(source.getAdjustType())) {
                CustomerDepositAccount account = depositAccountMapper.getByCustomerAndStation(source.getCustomerId(), source.getStationId());
                if (source.getAmount() == null || source.getAmount().signum() <= 0 || account == null
                        || account.getBalance() == null || account.getBalance().compareTo(source.getAmount()) < 0) {
                    return "当前押金余额不足以撤销原补录，请核对押金流水";
                }
            } else if (AdjustType.OVER_ADJUST.equals(source.getAdjustType())) {
                if (source.getQty() == null || (long) barrelLedgerService.rightQty(source.getCustomerId(), source.getStationId(), source.getProductId())
                        + barrelLedgerService.overQty(source.getCustomerId(), source.getStationId(), source.getProductId()) - source.getQty() < 0) {
                    return "撤销后客户实物桶数会为负，请核对交桶记录";
                }
            }
            return null;
        } catch (BusinessException unavailable) {
            return unavailable.getMessage();
        }
    }

    private static String statusTextOf(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "PENDING" -> "待执行";
            case "EFFECTIVE" -> "已生效";
            case "REVERSED" -> "已撤销";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }

    private void validateClientToken(String token) {
        if (token == null || token.isBlank() || token.length() > 64) {
            throw new BusinessException("请提供有效的调整凭据（不超过64字符）");
        }
    }

    /** 保留历史全局唯一键；命中其他站或另一用途时明确拒绝，绝不返回其单据。 */
    private void requireSameRequest(StationAdjustment a, Long stationId, Long customerId, String type,
                                    Long productId, Integer qty, BigDecimal amount, BigDecimal unitPrice,
                                    String reason, String evidence, Long reverses) {
        if (!Objects.equals(stationId, a.getStationId())) {
            throw new BusinessException("该调整凭据不可用于当前水站");
        }
        if (!Objects.equals(customerId, a.getCustomerId()) || !Objects.equals(type, a.getAdjustType())
                || !Objects.equals(productId, a.getProductId()) || !Objects.equals(qty, a.getQty())
                || !sameDecimal(amount, a.getAmount()) || !sameDecimal(unitPrice, a.getUnitPrice())
                || !Objects.equals(reason, a.getReason()) || !Objects.equals(evidence, a.getEvidence())
                || !Objects.equals(reverses, a.getReverses())) {
            throw new BusinessException("同一调整凭据不能用于不同内容或其他冲正来源");
        }
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void validatePersistedMoney(BigDecimal value, String label) {
        // 2026-10-08：schema.sql 两列均为 DECIMAL(10,2)，隐式舍入会让原请求重试与存储内容不一致。
        if (value != null && (value.stripTrailingZeros().scale() > 2
                || value.abs().compareTo(new BigDecimal("99999999.99")) > 0)) {
            throw new BusinessException(label + "须可按分无损保存，且不能超过数据库支持范围");
        }
    }

    private String mirrorType(String type) {
        switch (type) {
            case AdjustType.BARREL_GRANT:   return AdjustType.BARREL_REVOKE;
            case AdjustType.BARREL_REVOKE:  return AdjustType.BARREL_GRANT;
            case AdjustType.OVER_ADJUST:    return AdjustType.OVER_ADJUST;
            case AdjustType.DEPOSIT_GRANT:  return AdjustType.DEPOSIT_DEDUCT;
            case AdjustType.DEPOSIT_DEDUCT: return AdjustType.DEPOSIT_GRANT;
            case AdjustType.TICKET_GRANT:   return AdjustType.TICKET_DEDUCT;
            case AdjustType.TICKET_DEDUCT:  return AdjustType.TICKET_GRANT;
            default: throw new BusinessException("不支持的调整类型：" + type);
        }
    }

    private Integer mirrorQty(StationAdjustment src) {
        if (src.getQty() == null) return null;
        return AdjustType.OVER_ADJUST.equals(src.getAdjustType()) ? -src.getQty() : src.getQty();
    }

    /* ==================== 查询 ==================== */

    @Override
    public Map<String, Object> list(Long customerId, int page, int size) {
        Long stationId = requireMyStation();
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        int offset = (p - 1) * s;
        List<StationAdjustment> rows = (customerId == null)
                ? adjustmentMapper.listByStation(stationId, offset, s)
                : adjustmentMapper.listByCustomer(stationId, customerId, offset, s);
        int total = (customerId == null)
                ? adjustmentMapper.countByStation(stationId)
                : adjustmentMapper.countByCustomer(stationId, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", rows);
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    @Override
    public StationAdjustment getById(Long id) {
        Long stationId = requireMyStation();
        StationAdjustment a = adjustmentMapper.getById(id);
        if (a == null || !stationId.equals(a.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        return a;
    }

    /* ==================== 内部工具 ==================== */

    private Long requireMyStation() {
        return AuthContext.requireStationId();
    }

    private void validateCustomer(Long customerId, Long stationId) {
        if (customerId == null) {
            throw new BusinessException("客户不能为空");
        }
        if (customerStationConfigMapper.getByCustomerAndStation(customerId, stationId) == null) {
            throw new BusinessException("该客户不属于本水站，无法调整其资产");
        }
    }

    /** 历史资产不要求商品仍在售，但不能将他站私有商品或非循环商品写进本站桶/押金账。 */
    private void validateProduct(String adjustType, Long productId, Long stationId) {
        if (!AdjustType.isValid(adjustType)) throw new BusinessException("不支持的调整类型：" + adjustType);
        if (productId == null) {
            if (AdjustType.requiresProduct(adjustType)) throw new BusinessException("该调整类型必须指定商品");
            return;
        }
        Product product = productMapper.getById(productId);
        if (product == null || (product.getOwnerStationId() != null
                && !Objects.equals(stationId, product.getOwnerStationId()))) {
            throw new BusinessException("商品不存在或不属于本水站可用商品");
        }
        if ((AdjustType.isBarrel(adjustType) || AdjustType.isDeposit(adjustType)) && !BarrelScope.isBarrel(product)) {
            throw new BusinessException("桶权益、欠桶和商品押金只能调整桶装水商品");
        }
    }

    private void validateFields(String adjustType, Long productId, Integer qty, BigDecimal amount, String reason) {
        if (!AdjustType.isValid(adjustType)) {
            throw new BusinessException("不支持的调整类型：" + adjustType);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("调整原因必填");
        }
        if (AdjustType.requiresProduct(adjustType) && productId == null) {
            throw new BusinessException("该调整类型必须指定商品");
        }
        if (AdjustType.isDeposit(adjustType)) {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("押金调整金额必须大于 0");
            }
        } else if (AdjustType.OVER_ADJUST.equals(adjustType)) {
            if (qty == null || qty == 0) {
                throw new BusinessException("欠桶调整量不能为 0（正=补记欠桶，负=核销欠桶）");
            }
        } else {
            if (qty == null || qty <= 0) {
                throw new BusinessException("数量必须大于 0（方向由调整类型决定）");
            }
        }
    }

    private Snapshot snapshot(Long customerId, String adjustType, Long productId) {
        return snapshot(customerId, adjustType, productId, false);
    }

    private Snapshot snapshot(Long customerId, String adjustType, Long productId, boolean current) {
        Long stationId = requireMyStation();
        Snapshot s = new Snapshot();
        if (productId != null) {
            if (current) {
                // 保持桶→水票→押金的固定顺序；仅本次要增减桶账时创建桶锁行。
                if (AdjustType.isBarrel(adjustType)) barrelLedgerService.lockRights(customerId, stationId, productId);
                var balance = barrelBalanceReader.currentBalanceForUpdate(customerId, stationId, productId);
                s.right = balance.right(); s.over = balance.over();
                // 桶调整也会先读票快照，不能等后续 TICKET_GRANT 才消除缺行 gap lock。
                // 中性账户不代表新增资产；已有数量、金额、更新时间均保持原值。
                ticketAccountMapper.lockOrCreate(customerId, stationId, productId);
                TicketAccount ticket = ticketAccountMapper.lockAccount(customerId, stationId, productId);
                s.ticket = ticket == null || ticket.getRemainQuantity() == null ? 0 : ticket.getRemainQuantity();
            } else {
                s.right = barrelLedgerService.rightQty(customerId, stationId, productId);
                s.over = barrelLedgerService.overQty(customerId, stationId, productId);
                for (TicketAccount ticket : ticketAccountService.listByCustomerAndStation(customerId, stationId)) {
                    if (productId.equals(ticket.getProductId()) && ticket.getRemainQuantity() != null)
                        s.ticket += ticket.getRemainQuantity();
                }
            }
        }
        // 认领最后才追加押金，前序桶/票快照也必须先固定账户，不能先锁缺行再升级插入。
        if (current) depositAccountMapper.lockOrCreate(customerId, stationId);
        CustomerDepositAccount account = current ? depositAccountMapper.getForUpdate(customerId, stationId)
                : depositAccountMapper.getByCustomerAndStation(customerId, stationId);
        s.deposit = account == null || account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
        s.occupied = s.right + s.over;
        return s;
    }

    /** 只读投影：按类型推算调整后的数值（用于快照与试算，不落库） */
    private Snapshot projectAfter(Snapshot before, String adjustType, Integer qty, BigDecimal amount,
                                  BigDecimal unitPrice, Long productId) {
        Snapshot s = before.copy();
        if (AdjustType.BARREL_GRANT.equals(adjustType)) {
            s.right = before.right + nz(qty);
            s.occupied = s.right + s.over;
        } else if (AdjustType.BARREL_REVOKE.equals(adjustType)) {
            s.right = before.right - nz(qty);
            s.occupied = s.right + s.over;
        } else if (AdjustType.OVER_ADJUST.equals(adjustType)) {
            s.over = before.over + nz(qty);
            s.occupied = s.right + s.over;
        } else if (AdjustType.DEPOSIT_GRANT.equals(adjustType)) {
            s.deposit = before.deposit.add(nz(amount));
        } else if (AdjustType.DEPOSIT_DEDUCT.equals(adjustType)) {
            s.deposit = before.deposit.subtract(nz(amount));
        } else if (AdjustType.TICKET_GRANT.equals(adjustType)) {
            s.ticket = before.ticket + nz(qty);
        } else if (AdjustType.TICKET_DEDUCT.equals(adjustType)) {
            s.ticket = before.ticket - nz(qty);
        }
        return s;
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** 调整前后的资产快照（落库为 JSON 串，供人工追查"到底改了什么"） */
    private static class Snapshot {
        int right;
        int over;
        int occupied;
        int ticket;
        BigDecimal deposit = BigDecimal.ZERO;

        Snapshot copy() {
            Snapshot s = new Snapshot();
            s.right = right; s.over = over; s.occupied = occupied; s.ticket = ticket; s.deposit = deposit;
            return s;
        }

        String toJson() {
            return "{\"right\":" + right + ",\"over\":" + over + ",\"occupied\":" + occupied
                    + ",\"ticket\":" + ticket + ",\"deposit\":" + deposit + "}";
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("right", right);
            m.put("over", over);
            m.put("occupied", occupied);
            m.put("ticket", ticket);
            m.put("deposit", deposit);
            return m;
        }
    }
}
