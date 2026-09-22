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
 *   <li>设该客户在**本站**的账期（{@code customer_station_config.due_days / settlement_cycle}，**站级**；
 *       v60 之前错放在客户级 {@code company_info.due_days}，会让 A 站设的账期在 B 站生效）；</li>
 *   <li>核销（收款 + 核销同一事务，见 {@code ReceivableService.settle}）；</li>
 *   <li>[v60] 把存量未结账单按新账期**重算**一遍（会留痕）——
 *       因为 {@code orders.due_date} 是下单时快照、之后只读，站长改了账期会发现"老单没变"。</li>
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
    /** 验资（风险等级 / 可赊额度）：只读、现算。 */
    private final com.example.aquaflow.service.CustomerRiskService customerRiskService;

    public ManagerReceivableController(ReceivableService receivableService, CustomerMapper customerMapper,
                                       com.example.aquaflow.service.CustomerRiskService customerRiskService) {
        this.receivableService = receivableService;
        this.customerMapper = customerMapper;
        this.customerRiskService = customerRiskService;
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

    /** 读该客户在**本站**的账期（{@code dueDays} 为空 = 未设账期 = 即时结清）。 */
    @GetMapping("/customers/{customerId}/credit-terms")
    public Result<Map<String, Object>> creditTerms(@PathVariable Long customerId) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        return Result.success(receivableService.creditTerms(customerId, AuthContext.requireStationId()));
    }

    /**
     * 设/清该客户在**本站**的账期。
     *
     * <p>⚠️ <b>只对之后下的单生效</b>：{@code orders.due_date} 是下单时快照、之后只读。
     * 站长改完发现"老单没变"是设计如此；要把未结账单也改过来，调下面的 {@code recalculate}。
     * 界面必须把这句话写给站长看，否则他会以为系统坏了。</p>
     *
     * @param body {@code {dueDays, settlementCycle}}；{@code dueDays} 为 0 或 null = 清除账期（现结）
     */
    @PutMapping("/customers/{customerId}/credit-terms")
    public Result<Map<String, Object>> setCreditTerms(@PathVariable Long customerId,
                                                      @RequestBody Map<String, Object> body) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        Object cycle = body.get("settlementCycle");
        return Result.success(receivableService.setCreditTerms(customerId, AuthContext.requireStationId(),
                asInteger(body.get("dueDays")), cycle == null ? null : String.valueOf(cycle)));
    }

    /**
     * [v60] 把该客户在本站**还没结清**的挂账单，按当前账期重算应付日期。
     *
     * <p>站长显式触发的补救动作（默认不会自动跑）：{@code orders.due_date} 是下单时快照，
     * 所以改了账期之后老单不会自己跟着变。每张被改动的单都会在 {@code orders.special_note}
     * 追加一行 {@code [账期重算] 旧日期 → 新日期（操作人 N）} —— 留痕，不新建表。</p>
     *
     * <p>返回 {@code changedCount}；现结客户没有可重算的挂账单，会返回业务错误而不是 0，
     * 免得站长以为"点了没用"。</p>
     */
    @PostMapping("/customers/{customerId}/credit-terms/recalculate")
    public Result<Map<String, Object>> recalculate(@PathVariable Long customerId) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        int changed = receivableService.recalculateDueDates(customerId, AuthContext.requireStationId(),
                AuthContext.getUserId());
        return Result.success(Map.of("changedCount", changed));
    }

    /**
     * [v60] 该客户在**本站**的信用画像（验资）：风险等级、可赊额度、已用额度、逾期情况。
     *
     * <p>只读、现算、<b>不落库</b> —— 欠款一结清，等级自然回到「正常」，不需要谁去点"解冻"
     * （见 {@code CustomerRiskService} 的类注释：手动开关会变成站长又要记得管的东西）。</p>
     */
    @GetMapping("/customers/{customerId}/risk")
    public Result<Map<String, Object>> risk(@PathVariable Long customerId) {
        String bad = ownershipError(customerId);
        if (bad != null) return Result.error(bad);
        return Result.success(customerRiskService.assess(customerId, AuthContext.requireStationId()));
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
