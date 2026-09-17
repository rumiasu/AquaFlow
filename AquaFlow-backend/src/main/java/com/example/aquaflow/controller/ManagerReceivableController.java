package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.service.ReceivableService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 应收账款：站长端。2026-09-17 新增（规格见 {@code docs/design/20} §1 的 A 层）。
 *
 * <p><b>站点一律取自 {@code AuthContext}，不接受请求参数里的 stationId</b>
 * （AGENTS §6：跨站校验以服务端刷新的 stationId 为准）。</p>
 *
 * <p>本类只做三件事，且都是"给已有待收款加账期维度"，<b>不新造金额口径</b>：</p>
 * <ol>
 *   <li>读台账（总览 / 明细）；</li>
 *   <li>设客户账期（{@code company_info.due_days}，客户级）；</li>
 *   <li>核销（收款 + 核销同一事务，见 {@code ReceivableService.settle}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>逾期只提醒、不改任何金额</b>。本类没有任何"按逾期加收/罚款"的分支 ——
 * 对齐本仓"欠桶只提醒不阻断"的既有风格，也避免把经营规则写死在代码里。</p>
 */
@RestController
@RequestMapping("/api/manager")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerReceivableController {

    private final ReceivableService receivableService;
    private final CustomerMapper customerMapper;

    public ManagerReceivableController(ReceivableService receivableService, CustomerMapper customerMapper) {
        this.receivableService = receivableService;
        this.customerMapper = customerMapper;
    }

    /** 本站应收账款总览：待收款合计 / 逾期合计 / 按客户的账龄明细。 */
    @GetMapping("/receivables")
    public Result<Map<String, Object>> overview() {
        return Result.success(receivableService.overview(AuthContext.requireStationId()));
    }

    /**
     * 待收款明细。
     *
     * @param customerId  可选，只看某个客户
     * @param onlyOverdue 可选，只看已过应付日期的
     */
    @GetMapping("/receivables/orders")
    public Result<List<Map<String, Object>>> orders(@RequestParam(required = false) Long customerId,
                                                    @RequestParam(required = false, defaultValue = "false")
                                                    boolean onlyOverdue) {
        return Result.success(receivableService.orders(
                AuthContext.requireStationId(), customerId, onlyOverdue));
    }

    /**
     * 核销：把选中的挂账订单收款并核销（同一事务，全成或全不成）。
     *
     * @param body {@code {customerId, orderIds:[...]}}
     */
    @PostMapping("/receivables/settle")
    public Result<Map<String, Object>> settle(@RequestBody Map<String, Object> body) {
        Long stationId = AuthContext.requireStationId();
        Long customerId = asLong(body.get("customerId"));
        List<Long> orderIds = asLongList(body.get("orderIds"));
        return Result.success(receivableService.settle(stationId, customerId, orderIds));
    }

    /** 读客户账期（{@code dueDays} 为空 = 未设账期 = 即时结清）。 */
    @GetMapping("/customers/{customerId}/credit-terms")
    public Result<Map<String, Object>> creditTerms(@PathVariable Long customerId) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        return Result.success(receivableService.creditTerms(customerId));
    }

    /**
     * 设/清客户账期。
     *
     * @param body {@code {dueDays}}；0 或 null = 清除账期
     */
    @PutMapping("/customers/{customerId}/credit-terms")
    public Result<Map<String, Object>> setCreditTerms(@PathVariable Long customerId,
                                                      @RequestBody Map<String, Object> body) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        return Result.success(receivableService.setCreditTerms(customerId, asInteger(body.get("dueDays"))));
    }

    /**
     * 客户必须归属本站。
     *
     * <p>判据走 {@code countCustomerOfStation}（绑定<b>或</b>本站订单，取并集）——
     * <b>不要</b>换成 {@code getStationCustomer}：那是客户画像口径（要求有订单），
     * 会把"还没下过单的新客户"整个挡在门外，而给新客户设账期恰恰是最常见的场景。
     * 2026-09-17 该写法已在客户特权上导致三个用例全红，详见
     * {@code CustomerMapper.countCustomerOfStation} 的注释。</p>
     */
    private String ownershipError(Long customerId) {
        if (customerId == null) {
            return "customerId 不能为空";
        }
        if (customerMapper.getById(customerId) == null) {
            return "客户不存在";
        }
        if (customerMapper.countCustomerOfStation(customerId, AuthContext.requireStationId()) == 0) {
            return "该客户不属于本水站";
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInteger(Object v) {
        Long l = asLong(v);
        return l == null ? null : l.intValue();
    }

    private static List<Long> asLongList(Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(ManagerReceivableController::asLong).toList();
    }
}
