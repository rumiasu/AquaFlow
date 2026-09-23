package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.AdjustType;
import com.example.aquaflow.constant.BarrelRecordType;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerDepositAccount;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.StationAdjustment;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationAdjustmentMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.BarrelLedgerService;
import com.example.aquaflow.service.DepositRecordService;
import com.example.aquaflow.service.StationAdjustmentService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.PriceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @Autowired private DepositRecordService depositRecordService;
    @Autowired private TicketAccountService ticketAccountService;
    @Autowired private BarrelRecordMapper barrelRecordMapper;
    @Autowired private CustomerDepositAccountMapper depositAccountMapper;
    @Autowired private CustomerStationConfigMapper customerStationConfigMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private AuditLogService auditLogService;

    /* ==================== 试算 ==================== */

    @Override
    public Map<String, Object> preview(Long customerId, String adjustType, Long productId,
                                       Integer qty, BigDecimal amount, BigDecimal unitPrice) {
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
    @Transactional(rollbackFor = Exception.class)
    public StationAdjustment create(Long customerId, String adjustType, Long productId,
                                    Integer qty, BigDecimal amount, BigDecimal unitPrice,
                                    String reason, String evidence, String clientToken) {
        Long stationId = requireMyStation();
        validateCustomer(customerId, stationId);
        validateFields(adjustType, productId, qty, amount, reason);

        StationAdjustment existing = adjustmentMapper.findByClientToken(clientToken);
        if (existing != null) {
            return existing;   // 幂等：同一 token 返回原单
        }

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
        a.setAdjustNo("TMP-" + Long.toHexString(System.nanoTime()));
        adjustmentMapper.insert(a);

        String no = String.format("ADJ%s-%06d",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")), a.getId());
        adjustmentMapper.setAdjustNo(a.getId(), no);
        a.setAdjustNo(no);
        return a;
    }

    /* ==================== 执行 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void execute(Long id) {
        Long stationId = requireMyStation();
        StationAdjustment a = adjustmentMapper.getById(id);
        if (a == null || !stationId.equals(a.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        if (!"PENDING".equals(a.getStatus())) {
            throw new BusinessException("调整单当前状态不可执行：" + a.getStatus());
        }
        validateCustomer(a.getCustomerId(), stationId);

        // 1) 先算前后快照（只读）
        Snapshot before = snapshot(a.getCustomerId(), a.getAdjustType(), a.getProductId());
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

        if (AdjustType.BARREL_GRANT.equals(type)) {
            BigDecimal price = resolveUnitPrice(a, productId);
            boolean inferred = (a.getUnitPrice() == null || a.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0);
            barrelLedgerService.createLot(customerId, stationId, productId, price, a.getQty(), null, operatorId,
                    BarrelLedgerService.LotOrigin.manual(inferred, note));
            writeBarrelRecord(a, stationId, BarrelRecordType.ADJUST_INCREASE, a.getQty(),
                    before.over, before.over, note);

        } else if (AdjustType.BARREL_REVOKE.equals(type)) {
            BarrelLedgerService.LotConsumption c = barrelLedgerService.consumeLots(
                    customerId, stationId, productId, a.getQty(), null, false);
            barrelLedgerService.decreaseRight(customerId, stationId, productId, a.getQty(), c.getAmount());
            writeBarrelRecord(a, stationId, BarrelRecordType.ADJUST_DECREASE, a.getQty(),
                    before.over, before.over,
                    note + "（按批次单价核销，合计 ¥" + c.getAmount()
                            + (c.isHasMigratedPrice() ? "，含历史推断单价批次" : "") + "）");

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
    private void writeBarrelRecord(StationAdjustment a, Long stationId, int recordType, int qty,
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
    @Transactional(rollbackFor = Exception.class)
    public StationAdjustment reverse(Long id, String reason, String clientToken) {
        Long stationId = requireMyStation();
        StationAdjustment src = adjustmentMapper.getById(id);
        if (src == null || !stationId.equals(src.getStationId())) {
            throw new BusinessException("调整单不存在或无权访问");
        }
        if (!"EFFECTIVE".equals(src.getStatus())) {
            throw new BusinessException("仅已生效的调整单可撤销，当前状态：" + src.getStatus());
        }

        StationAdjustment rev = create(src.getCustomerId(), mirrorType(src.getAdjustType()),
                src.getProductId(), mirrorQty(src), src.getAmount(), src.getUnitPrice(),
                "撤销 " + src.getAdjustNo() + "：" + (reason == null ? "" : reason),
                null, clientToken);
        rev.setReverses(src.getId());
        // [2026-09-14 修] create() 内部已经执行过 insert，上面那句只改了内存对象、不会落库，
        // 导致 reverses 列恒为 NULL（反向单反查不到原单，审计链断一半）。必须显式再 update 一次。
        adjustmentMapper.setReverses(rev.getId(), src.getId());
        execute(rev.getId());

        int marked = adjustmentMapper.markReversedIf(src.getId(), "EFFECTIVE", "REVERSED", rev.getId());
        if (marked == 0) {
            throw new BusinessException("原单状态已变更，撤销失败");
        }
        auditLogService.log("ADJUST", "REVERSE", "adjust:" + id,
                "reversedBy=" + rev.getId() + ",reason=" + reason, null);
        return rev;
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
        Long stationId = requireMyStation();
        Snapshot s = new Snapshot();
        if (productId != null) {
            s.right = barrelLedgerService.rightQty(customerId, stationId, productId);
            s.over = barrelLedgerService.overQty(customerId, stationId, productId);
        }
        CustomerDepositAccount acc = depositAccountMapper.getByCustomerAndStation(customerId, stationId);
        s.deposit = (acc != null && acc.getBalance() != null) ? acc.getBalance() : BigDecimal.ZERO;
        if (productId != null) {
            List<TicketAccount> tickets = ticketAccountService.listByCustomerAndStation(customerId, stationId);
            int tq = 0;
            for (TicketAccount t : tickets) {
                if (productId.equals(t.getProductId()) && t.getRemainQuantity() != null) {
                    tq += t.getRemainQuantity();
                }
            }
            s.ticket = tq;
        }
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
