package com.example.aquaflow.service;

import com.example.aquaflow.dto.PayrollDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 站长工资预览与线下发薪登记；原工资流水和单据状态仍是真相源。 */
@Service
@RequiredArgsConstructor
public class PayrollPreviewService {
    private final PayrollPreviewMapper previewMapper;
    private final StaffMapper staffMapper;
    private final StaffEarningMapper earningMapper;
    private final StaffPayrollMapper payrollMapper;
    private final StaffPieceRateMapper rateMapper;
    private final StaffEarningService earningService;

    /** 不生成、挂接、确认单据；日期包含首尾日。 */
    @Transactional(readOnly = true)
    public Map<String,Object> preview(Long stationId, Long staffId, LocalDate start, LocalDate end) {
        return calculate(stationId, staffId, start, end, false);
    }

    private Map<String,Object> calculate(Long stationId, Long staffId, LocalDate start, LocalDate end, boolean locking) {
        validatePeriod(staffId, start, end);
        Staff staff = locking ? staffMapper.getByIdForUpdate(staffId) : staffMapper.getById(staffId);
        if (!(staff != null && stationId.equals(staff.getStationId()) && Set.of("DELIVERY", "STATION_MANAGER").contains(staff.getRole()))
                && earningMapper.countByStationAndStaff(stationId, staffId) == 0)
            throw new BusinessException("该员工与本站没有工资结算关系");
        List<StaffEarning> rows = previewMapper.earnings(stationId, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), locking);
        Map<Long,StaffPayroll> payrolls = new TreeMap<>();
        BigDecimal unassigned = BigDecimal.ZERO, unpaid = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        BigDecimal recorded = rows.stream().map(StaffEarning::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean unknown = false, hasPending = false;
        for (StaffEarning row : rows) {
            if (row.getPayrollId() == null) { unassigned = unassigned.add(row.getAmount()); unpaid = unpaid.add(row.getAmount()); continue; }
            StaffPayroll p = payrolls.computeIfAbsent(row.getPayrollId(), id -> previewMapper.payroll(stationId, id, locking));
            if (p == null || p.getStatus() == null || !Set.of(1,2,3).contains(p.getStatus())) { unknown = true; continue; }
            if (p.getStatus() == StaffPayroll.Status.PAID) paid = paid.add(row.getAmount());
            else { unpaid = unpaid.add(row.getAmount()); hasPending = true; }
        }
        for (StaffPayroll p : payrolls.values()) {
            if (p.getTotalAmount() == null || p.getTotalAmount().compareTo(earningMapper.sumByPayroll(p.getId())) != 0) unknown = true;
        }
        boolean missingRule = rateMapper.listByStation(stationId).isEmpty();
        int unrecorded = previewMapper.unrecordedDeliveries(stationId, staffId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        StaffPayroll samePeriod = payrollMapper.getByPeriod(stationId, staffId, start, end);
        String warning = unknown ? "有工资记录状态待核实，请核对历史记录"
                : unrecorded > 0 ? "有已送达商品尚无计件流水，请核实当时单价并补录差额；不能按现价自动追算"
                : missingRule ? "尚未配置计件单价，本期应发待核实；已记录金额仍可查看"
                : hasPending ? "本期包含历史未发工资，请先在历史记录中核对并结算，避免重复发薪"
                : samePeriod != null ? "此期间已有结算记录，请核对历史；新增差额需按实际发生期间单独处理"
                : unassigned.signum() <= 0 ? "本期没有待发的正金额，无需登记发薪；如有扣项请核对明细" : "";
        boolean complete = !missingRule && unrecorded == 0 && !unknown;
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("staffId", staffId); out.put("staffName", staff == null ? "员工#" + staffId : staff.getName());
        out.put("periodStart", start); out.put("periodEnd", end);
        out.put("recordedAmount", recorded); out.put("payableAmount", complete ? recorded : null); out.put("statusKnown", !unknown);
        out.put("unpaidAmount", unpaid); out.put("paidAmount", paid); out.put("settleAmount", unassigned);
        out.put("complete", complete); out.put("missingRule", missingRule); out.put("unrecordedCount", unrecorded);
        out.put("statusText", complete ? "本期应发" : "本期应发待核实"); out.put("warning", warning);
        out.put("canSettle", complete && warning.isEmpty() && unassigned.signum() > 0);
        out.put("earnings", rows);
        out.put("previewToken", digest(stationId + ":" + staffId + ":" + start + ":" + end + ":"
                + rowsToken(rows) + ":" + payrolls.values().stream().map(p -> p.getId() + ":" + p.getStatus() + ":" + p.getTotalAmount()).toList()
                + ":" + missingRule + ":" + unrecorded + ":" + (samePeriod == null ? "" : samePeriod.getId())));
        return out;
    }

    /** 确认人已在线下付款后，一笔事务生成、锁定并登记发放；变动的预览必须重看。 */
    @Transactional(rollbackFor = Exception.class)
    public StaffPayroll settle(Long stationId, PayrollDTO.Settle body) {
        requireOffline(body);
        Map<String,Object> now = calculate(stationId, body.getStaffId(), body.getPeriodStart(), body.getPeriodEnd(), true);
        requireToken(body.getPreviewToken(), now.get("previewToken"));
        if (!Boolean.TRUE.equals(now.get("canSettle"))) throw new BusinessException("本期暂不能结算，请重新核对预览与历史记录");
        Long id;
        try { id = earningService.generatePayroll(stationId, body.getStaffId(), body.getPeriodStart(), body.getPeriodEnd(), body.getNote()); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw new BusinessException("本期已生成工资记录，请刷新历史核对，勿重复发薪"); }
        // 2026-10-27：范围锁后仍核对实际挂接集合，防止旧调用并发归集造成少发、空单或金额改变。
        if (!rowsToken(earningMapper.listByPayroll(id)).equals(rowsToken(((List<StaffEarning>) now.get("earnings")).stream()
                .filter(e -> e.getPayrollId() == null).toList()))) throw new BusinessException("工资明细已变化，请重新查看应发金额");
        earningService.confirmPayroll(stationId, id);
        earningService.markPayrollPaid(stationId, id);
        return payrollMapper.getById(id);
    }

    /** 历史草稿/已确认单可直接核对金额再登记发放，不改写已发单。 */
    @Transactional(readOnly = true)
    public Map<String,Object> historicalPreview(Long stationId, Long id) { return historical(stationId, id, false); }

    private Map<String,Object> historical(Long stationId, Long id, boolean locking) {
        StaffPayroll p = previewMapper.payroll(stationId, id, locking);
        if (p == null) throw new BusinessException("工资记录不存在或不属于本站");
        List<StaffEarning> rows = earningMapper.listByPayroll(id);
        BigDecimal total = rows.stream().map(StaffEarning::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean canSettle = Set.of(StaffPayroll.Status.DRAFT, StaffPayroll.Status.CONFIRMED).contains(p.getStatus())
                && total.signum() > 0 && total.compareTo(p.getTotalAmount()) == 0;
        Staff staff = staffMapper.getById(p.getStaffId());
        return Map.of("payroll", p, "staffName", staff == null ? "员工#" + p.getStaffId() : staff.getName(),
                "canSettle", canSettle, "previewToken", digest(p.getId() + ":" + p.getStatus() + ":" + p.getTotalAmount() + ":" + rowsToken(rows)));
    }

    /** 历史单沿用状态 CAS 和发放审计；已发单重复请求明确拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public StaffPayroll settleHistorical(Long stationId, Long id, PayrollDTO.Settle body) {
        requireOffline(body);
        Map<String,Object> now = historical(stationId, id, true);
        requireToken(body.getPreviewToken(), now.get("previewToken"));
        if (!Boolean.TRUE.equals(now.get("canSettle"))) throw new BusinessException("该工资记录已发放或金额待核实，请刷新后核对");
        StaffPayroll p = (StaffPayroll) now.get("payroll");
        if (p.getStatus() == StaffPayroll.Status.DRAFT) earningService.confirmPayroll(stationId, id);
        earningService.markPayrollPaid(stationId, id);
        return payrollMapper.getById(id);
    }

    private static void validatePeriod(Long staff, LocalDate start, LocalDate end) {
        if (staff == null || start == null || end == null) throw new BusinessException("请选择员工和工资期间");
        if (end.isBefore(start) || end.isAfter(LocalDate.of(2998,12,31)) || start.isBefore(LocalDate.of(1970,1,1)))
            throw new BusinessException("工资期间无效，请核对开始和结束日期");
    }
    private static void requireOffline(PayrollDTO.Settle body) {
        if (!Boolean.TRUE.equals(body.getPaidOffline())) throw new BusinessException("请先核实已在线下实际发薪，再登记结算");
        if (body.getNote() != null && body.getNote().length() > 200) throw new BusinessException("工资说明最多200字");
    }
    private static void requireToken(String expected, Object current) {
        if (expected == null || !expected.equals(current)) throw new BusinessException("工资明细或状态已变化，请重新查看金额后确认");
    }
    private static String rowsToken(List<StaffEarning> rows) {
        return rows.stream().sorted(Comparator.comparing(StaffEarning::getId))
                .map(e -> e.getId() + ":" + e.getAmount().toPlainString() + ":" + e.getKind() + ":" + e.getCreateTime()).toList().toString();
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
