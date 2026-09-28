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
