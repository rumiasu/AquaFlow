package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.InterStationSettlementService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 站间结算台账：站长端（v67）。
 * 正本口径：{@code docs/design/31-站间结算算例-水票计价-决策件.md}（§6.2 算法 / §8 已拍板）。
 *
 * <p><b>回答的问题</b>：跨站外派单（归属 A / 履约 B）的钱收在 A、营收算 B，
 * 「谁欠谁、欠多少、什么时候算办完」此前<b>系统里没有任何一处能回答</b>
 * （实测：站2 名下 0 条支付流水，看板却显示一笔"已收款"）。</p>
 *
 * <p><b>站点一律取自 {@code AuthContext}</b>，不接受请求参数里的 stationId
 * （AGENTS §6：跨站校验以服务端刷新的 stationId 为准）。</p>
 *
 * <p>⚠️ <b>谁能做什么，由服务端判权、不看前端按钮</b>：</p>
 * <ul>
 *   <li>{@code settle}（登记结清）—— 只有**付款方**（钱在它手上的那一站）能做；</li>
 *   <li>{@code price-by-listed}（改按挂牌价）—— 只有**卖票站**（本单归属站）能做，
 *       因为差价由它自己承担（{@code docs/design/31} §8.2）；</li>
 *   <li>{@code reverse}（冲销）—— 台账任一方都能做（订单取消后那笔应付不再成立）。</li>
 * </ul>
 *
 * <p>⚠️ <b>系统不假装打款</b>：钱是两站之间线下转的，本接口只落"谁在什么时候登记了结清、
 * 凭据说明是什么"（同"配送员工资只记 {@code paid_time}"的既有先例）。</p>
 */
@RestController
@RequestMapping("/api/manager")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerInterStationSettlementController {
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.UnusedTicketRefundService ticketExits;
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.BarrelBusinessPolicy barrelPolicy;
    @GetMapping("/ticket-exit-batches") public Result<java.util.List<Map<String,Object>>> ticketExitBatches() {
        return Result.success(barrelPolicy.hasSchema()?ticketExits.candidates(AuthContext.requireStationId()):java.util.List.of());
    }
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.BusinessWaitingService businessWaiting;
    /** 站长端业务待办页：缺货待补及审批/退款超时，只提示，不抹去已收桶事实。 */
    @GetMapping("/business-waiting") public Result<Map<String,Object>> waiting() { return Result.success(businessWaiting.waiting(AuthContext.requireStationId())); }
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.DispatchAgreementService dispatchAgreements;
    /** 员工端每单外包报价；来源站提价，接收站接单时固定，只含钱货去向。 */
    @GetMapping("/dispatch-agreements/{orderId}") public Result<Map<String,Object>> agreement(@PathVariable Long orderId) {
        return Result.success(dispatchAgreements.authorizedInfo(orderId,AuthContext.requireStationId()));
    }
    @PutMapping("/dispatch-agreements/{orderId}") public Result<Void> quote(@PathVariable Long orderId,@jakarta.validation.Valid @RequestBody com.example.aquaflow.dto.DispatchQuoteDTO dto) {
        dispatchAgreements.quote(orderId,dto); return Result.success();
    }
    @GetMapping("/station-barrel-balances") public Result<java.util.List<Map<String,Object>>> barrelBalances() {
        return Result.success(dispatchAgreements.barrelBalances(AuthContext.requireStationId()));
    }
    @PostMapping("/station-barrel-balances/{orderId}/dispute") public Result<Void> dispute(@PathVariable Long orderId,@RequestBody Map<String,String> body) {
        dispatchAgreements.dispute(orderId,AuthContext.requireStationId(),body.get("note")); return Result.success();
    }
    @PutMapping("/station-barrel-balances/{orderId}/proposal") public Result<Void> proposal(@PathVariable Long orderId,@jakarta.validation.Valid @RequestBody com.example.aquaflow.dto.BarrelResolutionDTO dto) {
        dispatchAgreements.propose(orderId,AuthContext.requireStationId(),dto); return Result.success();
    }
    @PostMapping("/station-barrel-balances/{orderId}/agree") public Result<Void> agree(@PathVariable Long orderId) {
        dispatchAgreements.agree(orderId,AuthContext.requireStationId()); return Result.success();
    }
    @PostMapping("/station-barrel-balances/{orderId}/received") public Result<Void> barrelReceived(@PathVariable Long orderId,@RequestBody Map<String,String> body) {
        dispatchAgreements.closeBarrels(orderId,AuthContext.requireStationId(),body.get("note")); return Result.success();
    }
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.StationRecoveryService recoveries;
    /** 员工端站间台账：冲销后已交付资金的追收，不把改状态当成打款。 */
    @GetMapping("/inter-station-recoveries") public Result<java.util.List<Map<String,Object>>> recoveries() {
        return Result.success(recoveries.list(AuthContext.requireStationId()));
    }
    @PostMapping("/inter-station-recoveries/{orderId}/sent") public Result<Void> recoverySent(@PathVariable Long orderId,@RequestBody Map<String,String> body) {
        recoveries.sent(orderId,AuthContext.requireStationId(),body.get("note")); return Result.success();
    }
    @PostMapping("/inter-station-recoveries/{orderId}/received") public Result<Void> recoveryReceived(@PathVariable Long orderId) {
        recoveries.received(orderId,AuthContext.requireStationId()); return Result.success();
    }

    private final InterStationSettlementService service;

    public ManagerInterStationSettlementController(InterStationSettlementService service) {
        this.service = service;
    }

    /** 本站站间结算台账：逐单「谁欠谁 / 欠多少 / 算没算完」+ 两个方向的合计。 */
    @GetMapping("/inter-station-settlements")
    public Result<Map<String, Object>> ledger() {
        return Result.success(service.ledgerOf(AuthContext.requireStationId()));
    }

    /**
     * 登记结清（付款方）：这笔已经付给对方了。
     *
     * @param body {@code {note}} —— 结清凭据说明（转账流水号 / 经手人），可选
     */
    @PostMapping("/inter-station-settlements/{orderId}/settle")
    public Result<Map<String, Object>> settle(@PathVariable Long orderId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return Result.success(service.settle(AuthContext.requireStationId(), orderId, note,
                AuthContext.getUserId()));
    }

    /**
     * 改按挂牌价（卖票站）：把本单的站间计价从默认的「折算实付」改成「按挂牌价」。
     *
     * <p>产品原话（2026-09-27）：「默认折算实付，主要依靠站长选吧，长时间没人接还是给站长弹提示
     * 是否按照挂牌价」—— 是**站长选**，不是系统自动改。</p>
     */
    @PostMapping("/inter-station-settlements/{orderId}/price-by-listed")
    public Result<Map<String, Object>> priceByListed(@PathVariable Long orderId) {
        return Result.success(service.priceByListed(AuthContext.requireStationId(), orderId,
                AuthContext.getUserId()));
    }

    /** 冲销：订单取消 / 退款后那笔站间应付不再成立（改状态、不删行，轨迹留得住）。 */
    @PostMapping("/inter-station-settlements/{orderId}/reverse")
    public Result<Map<String, Object>> reverse(@PathVariable Long orderId) {
        return Result.success(service.reverse(AuthContext.requireStationId(), orderId));
    }
}
