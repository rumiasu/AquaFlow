package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.PayrollDTO;
import com.example.aquaflow.entity.StaffEarning;
import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.entity.StaffPieceRate;
import com.example.aquaflow.mapper.StaffEarningMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.mapper.StaffPieceRateMapper;
import com.example.aquaflow.service.StaffEarningService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员计件工资：站长端。2026-09-17 新增（v37）。规格见 {@code docs/design/18}。
 *
 * <p><b>站点一律取自 {@code AuthContext.requireStationId()}</b>，忽略请求体里的 stationId ——
 * 否则站长能看到、甚至改写别的站的工资台账。</p>
 *
 * <p><b>发钱是线下动作</b>（微信转账/现金），本控制器只做两件事：算清楚、留痕迹。
 * 没有打款/提现/钱包接口 —— 那要支付牌照与资金存管，而本项目连微信支付渠道都还没接。</p>
 */
@RestController
@RequestMapping("/api/manager")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerPayrollController {

    @Autowired private StaffPieceRateMapper staffPieceRateMapper;
    @Autowired private StaffEarningMapper staffEarningMapper;
    @Autowired private StaffPayrollMapper staffPayrollMapper;
    @Autowired private StaffEarningService staffEarningService;

    /** 读本站计件单价（productId 省略 = 该站默认价）。没配过时返回全 0 的默认配置而不是 404。 */
    @GetMapping("/piece-rate")
    public Result<Map<String, Object>> getPieceRates(@RequestParam(required = false) Long productId) {
        Long stationId = AuthContext.requireStationId();
        Map<String, Object> data = new HashMap<>();
        data.put("rates", staffPieceRateMapper.listByStation(stationId));
        if (productId != null) {
            StaffPieceRate r = staffPieceRateMapper.getByStationAndProduct(stationId, productId);
            data.put("configured", r != null);
            data.put("rate", r != null ? r : StaffPieceRate.defaults(stationId, productId));
        }
        return Result.success(data);
    }

    /** 保存计件单价。负值一律归零（负数工钱会让站长倒欠配送员，属能少付钱的输入，必须挡住）。 */
    @PutMapping("/piece-rate")
    public Result<StaffPieceRate> savePieceRate(@RequestBody PayrollDTO.PieceRate body) {
        Long stationId = AuthContext.requireStationId();
        Long productId = body.getProductId() != null ? body.getProductId() : 0L;

        StaffPieceRate r = StaffPieceRate.defaults(stationId, productId);
        r.setPerBucketAmount(nn(body.getPerBucketAmount()));
        r.setReturnBucketAmount(nn(body.getReturnBucketAmount()));
        r.setFloorBonusPerLevel(nn(body.getFloorBonusPerLevel()));
        r.setFloorFreeLevel(nn(body.getFloorFreeLevel()));
        r.setPerOrderAmount(nn(body.getPerOrderAmount()));
        r.setPenaltyPerBucket(nn(body.getPenaltyPerBucket()));
        staffPieceRateMapper.upsert(r);
        log.info("[v37] 站长保存计件单价: stationId={}, productId={}, perBucket={}, floorBonus={}, penalty={}",
                stationId, productId, r.getPerBucketAmount(), r.getFloorBonusPerLevel(), r.getPenaltyPerBucket());
        return Result.success(staffPieceRateMapper.getByStationAndProduct(stationId, productId));
    }

    /** 某配送员未结算的收益明细 + 未结合计（前端提示"还有多少没结"）。 */
    @GetMapping("/earnings")
    public Result<Map<String, Object>> listEarnings(@RequestParam Long staffId,
                                                    @RequestParam(required = false) Long payrollId) {
        Long stationId = AuthContext.requireStationId();
        Map<String, Object> data = new HashMap<>();
        if (payrollId != null) {
            // 结算单明细：必须校验该结算单属于本站（跨站查询 = 越权知情）
            StaffPayroll p = staffPayrollMapper.getById(payrollId);
            if (p == null || !p.getStationId().equals(stationId)) {
                return Result.error("结算单不存在或不属于本站");
            }
            data.put("earnings", staffEarningMapper.listByPayroll(payrollId));
        } else {
            // 未结算的：只按 (station, staff) 过滤，不带时间窗（时间窗由生成结算单时决定）
            data.put("earnings", staffEarningMapper.listUnsettled(stationId, staffId,
                    java.time.LocalDateTime.of(1970, 1, 1, 0, 0), java.time.LocalDateTime.of(2999, 1, 1, 0, 0)));
        }
        data.put("unsettledTotal", staffEarningMapper.sumUnsettled(stationId, staffId));
        return Result.success(data);
    }

    /** 生成结算单：把该期间内未结算的明细挂上去并算合计。 */
    @PostMapping("/payroll")
    public Result<Map<String, Object>> generatePayroll(@RequestBody PayrollDTO.Generate body) {
        Long stationId = AuthContext.requireStationId();
        Long id = staffEarningService.generatePayroll(stationId, body.getStaffId(),
                body.getPeriodStart(), body.getPeriodEnd(), body.getNote());
        Map<String, Object> data = new HashMap<>();
        data.put("payrollId", id);
        data.put("payroll", staffPayrollMapper.getById(id));
        return Result.success(data);
    }

    @GetMapping("/payroll")
    public Result<List<StaffPayroll>> listPayrolls(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(staffPayrollMapper.listByStation(AuthContext.requireStationId(),
                Math.min(Math.max(limit, 1), 500)));
    }

    /** 确认结算单（草稿 → 已确认）。确认后明细锁定。 */
    @PostMapping("/payroll/{id}/confirm")
    public Result<Void> confirmPayroll(@PathVariable Long id) {
        staffEarningService.confirmPayroll(AuthContext.requireStationId(), id);
        return Result.success();
    }

    /**
     * 标记已发放（已确认 → 已发放）。
     *
     * <p>发钱本身在线下完成（微信转账/现金），这里只落发放时间与操作人 ——
     * 没有这个痕迹，下个月站长就说不清"这笔到底发没发过"。</p>
     */
    @PostMapping("/payroll/{id}/pay")
    public Result<Void> payPayroll(@PathVariable Long id) {
        staffEarningService.markPayrollPaid(AuthContext.requireStationId(), id);
        return Result.success();
    }

    /** 人工调整：补一笔或扣一笔（唯一允许自带符号的入口）。 */
    @PostMapping("/payroll/adjust")
    public Result<Void> adjustEarning(@RequestBody PayrollDTO.Adjust body) {
        staffEarningService.adjustEarning(AuthContext.requireStationId(), body.getStaffId(),
                body.getAmount(), body.getNote());
        return Result.success();
    }

    private static BigDecimal nn(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private static Integer nn(Integer v) {
        return v == null ? null : Math.max(0, v);
    }
}
