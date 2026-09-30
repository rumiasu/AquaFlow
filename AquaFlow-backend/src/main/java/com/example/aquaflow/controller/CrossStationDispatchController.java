package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.service.DeliveryConsoleService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨站外派面（F-18 由 {@code DeliveryController} 按读者拆出）。
 *
 * <p>本类只服务「一单要在两个水站之间交接」这件事：抢单池、指定外派（发出去 / 收进来）、
 * 外派追踪、召回、指定退回三段式（发起 / 同意 / 拒绝）、以及提交前的跨站风险查询。
 * 它是全仓<b>唯一的跨租户可见面</b> —— 下发给别站站长的只许「钱货去向」文案与订单快照金额，
 * 不许带归属站的成本 / 库存 / 联系方式，也不许下发客户画像（AGENTS §1.1）。</p>
 *
 * <p>配送员自助面见 {@code DeliveryTaskController}，站长控制台面见
 * {@code StationDeliveryConsoleController} —— 三者共用类级前缀 {@code /api/delivery}
 * 与 {@code DeliveryConsoleService}，<b>路径与方法一律与拆分前逐字相同</b>。</p>
 *
 * <p>⚠️ 本类<b>不注入任何 Mapper</b>（分层门禁只减不增）：一切取数走
 * {@link DeliveryConsoleService}，一切状态写入走 {@link OrderWorkflowService}。</p>
 */
@RestController
@RequestMapping("/api/delivery")
public class CrossStationDispatchController {

    @Autowired
    private DeliveryConsoleService deliveryConsoleService;

    @Autowired
    private OrderWorkflowService orderWorkflowService;

    /**
     * 外派订单：临时指派给其他水站配送，客户归属不变
     * 仅修改 delivery_station_id，owner_station_id 保持不变
     *
     * <p>[2026-09-18] 涉押金/桶权益的单必须带 {@code riskAcknowledged=true}（外派方显式确认风险），
     * 否则 service 直接拒（{@code code=1}）且不产生任何副作用；普通单不看这个字段。
     * 接收站还要在「分配配送员」时确认一次（{@code Assign.riskAcknowledged}），两侧都进 special_note。</p>
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/dispatch")
    public Result<Void> dispatchOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Dispatch body) {
        Long targetStationId = body.getTargetStationId();
        String reason = body.getReason() != null ? body.getReason() : "外派配送";
        orderWorkflowService.dispatchExternal(id, targetStationId, reason,
                Boolean.TRUE.equals(body.getRiskAcknowledged()));
        return Result.success();
    }

    /**
     * 跨站外派风险查询（员工端「提交前提示」用）：本单涉不涉押金/桶权益、后端下发的提示文案是什么。
     *
     * <p><b>为什么要一个只读端点</b>：会出现「外派 / 接单」按钮的四张列表形状不一（抢单池是 Map，
     * 指定外派 / 待分配 / 外派追踪是实体直出），而风险文案的判据与文案都只该在服务端有一份
     * （{@code OrderWorkflowServiceImpl.involvesDepositOrBarrelRights} / {@code DEPOSIT_BARREL_RISK_TEXT}）。
     * 前端在**点了操作之后、真正提交之前**问一次，把 {@code riskNote} 原样展示，
     * 确认后再带 {@code riskAcknowledged=true} 提交 —— 前端不自己判断"这单算不算涉押金"。</p>
     *
     * <p>归属校验与订单详情同口径：本站履约 <b>或</b> 本站归属（抢单池里的单由归属站外派，
     * 接收站看的是本站履约）。查不到 / 他站的单直接拒，避免变成"按 id 探测订单是否存在"的探针。</p>
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/{id}/cross-station-risk")
    public Result<Map<String, Object>> getCrossStationRisk(@PathVariable Long id) {
        Orders order = deliveryConsoleService.findOrder(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        Long stationId = AuthContext.requireStationId();
        boolean mine = stationId.equals(StationUtil.deliveryStation(order)) || stationId.equals(order.getStationId());
        if (!mine) {
            return Result.error("无权查看他站订单");
        }
        String note = orderWorkflowService.crossStationRiskNote(order);
        // `crossStation` = 这一单的**归属站不是当前站**（事实，不是"要不要弹框"的建议）。
        //
        // ⚠️ [2026-09-27 产品裁定] 前端**不能**拿 `riskNote != null` 当"要不要弹框"的判据：
        //    riskNote 只看"涉不涉押金"，而**本站单从来不需要确认**（后端 assignToStaff 的
        //    risky = 跨站 && 涉押金）。拿它当判据的后果是：站内部分配也弹一次押金提醒（纯摩擦），
        //    而真正需要确认的跨站单与不需要确认的本站单**长得一模一样**——前端只能靠猜。
        //    所以这里下发事实，**由前端按本次操作声明意图**（外派要确认 / 受理本站内分配只在跨站时要），
        //    口径仍然只有服务端一份（涉不涉押金、是不是跨站都由这里算）。
        boolean crossStationHere = order.getStationId() != null && !order.getStationId().equals(stationId);
        Map<String, Object> data = new HashMap<>();
        data.put("depositBarrelRisk", note != null);
        data.put("riskNote", note);
        data.put("crossStation", crossStationHere);
        return Result.success(data);
    }

    /**
     * 站长指定水站外派 / 放入抢单池。
     *
     * <p>[2026-09-18] 与 {@code /orders/{id}/dispatch} 是同一件事的两个入口：
     * 涉押金/桶权益的单 → 入池<b>直接拒</b>（{@code targetStationId} 为空时，即使带了确认也拒）；
     * 指定水站时必须有 {@code riskAcknowledged=true}。只堵一个入口等于没堵。</p>
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer/{id}/outsource")
    public Result<Void> outsourceOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Outsource body) {
        String reason = body.getReason() != null ? body.getReason() : "站长指定水站外派";
        orderWorkflowService.outsource(id, body.getTargetStationId(), reason,
                Boolean.TRUE.equals(body.getRiskAcknowledged()));
        return Result.success();
    }

    /**
     * 抢单池列表：获取同城市+距离范围内外派订单
     * 仅返回 delivery_station_id IS NULL 的订单
     * 包含商品匹配信息（辅助提示，不硬拦截）
     *
     * <p>[2026-09-18] 站长的产品裁定：「配送费是站长说了算，跨站外派仍按<b>本站（外派方）</b>定价，
     * 但钱去向实际配送的履约站，而且<b>抢单前要一眼看到</b>」。所以本列表在原有的地址/数量之外
     * 追加下发四项<b>金额与去向</b>信息（字段与来源见 {@code DeliveryConsoleService} 的实现）。</p>
     *
     * <p>⚠️ <b>金额一律取 {@code orders} 上的快照列，绝不在这里重算</b> ——
     * 抢单池里的单是归属站按<b>它自己的</b>站级配置算完快照下来的。在这里调一次
     * {@code DeliveryFeeService.calcForOrder} 就是"计价双轨"（本仓最贵的一次事故，
     * 见 {@code util/PriceUtil} 文件头）：认领前后金额会变、抢单页与订单详情会显示两个价。</p>
     *
     * <p>⚠️ <b>客户画像不下发</b>（2026-09-18 产品裁定）：「订单有关的所有信息可查，但<b>没有在本站
     * 绑定过（主动选择下单）的客户，均不能看客户画像</b> —— 他没在本站下过单就等于没有本站画像，
     * 有也是别站的画像，跨站要隔离」。{@code listPoolOrders} 的 SQL 里 `left join customer c`
     * 会把<b>别站客户</b>的姓名与手机号带出来，等于任何站长都能看到别站客户的联系方式 ——
     * 服务层显式置 {@code null}。订单自身的配送信息（{@code receiverName} / {@code receiverPhone} /
     * {@code addressDetail} / {@code addressSnapshot}）与商品金额<b>照常下发</b>：
     * 那些是"订单有关的信息"，跨站配送必需。同一口径见 {@code getDirectedIncoming}。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/pool")
    public Result<?> getPoolOrders() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(deliveryConsoleService.listPoolOrders(stationId));
    }

    /**
     * 从抢单池抢单：指定配送员接单
     * delivery_station_id = 本站，订单变为本站配送
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/claim-pool")
    public Result<Void> claimPoolOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.ClaimPool body) {
        orderWorkflowService.claimPool(id, body.getDeliveryStaffId());
        return Result.success();
    }

    /**
     * 外派列表：本站外派出去的订单状态。
     *
     * <p>[2026-09-26] 首页「外派」页签内置两个子页签（<b>一键外派</b> / <b>指定外派</b>），
     * 由本端点下发的 {@code dispatchKind} 分流 —— 归类实现只有一处
     * （{@code constant.DispatchKind.ofNote}，判据是备注文案，理由见那个枚举的注释），
     * <b>前端不要自己解析 specialNote</b>：自由文本一旦在两端各判一次，迟早分叉。</p>
     *
     * <p>顺带补 {@code deliveryStationName}（外派至哪个站）：该字段不是数据库列，
     * 本列表的 SQL 也没有 join 站表 —— 不在这里填，界面上的「外派至」整行永远不显示
     * （实测就是这样：站长只能看到一个订单号，判断不了这单派给了谁）。池中还没人接的单
     * {@code delivery_station_id} 为空，站点名保持 null，前端据此显示"等别站接单"。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/dispatch-tracking")
    public Result<?> getDispatchTracking() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(deliveryConsoleService.listDispatchTracking(stationId));
    }

    /**
     * 取消外派：将订单从抢单池移除，恢复为本站待分配
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/cancel-dispatch")
    public Result<Void> cancelDispatch(@PathVariable Long id) {
        orderWorkflowService.cancelDispatch(id);
        return Result.success();
    }

    /**
     * 指定水站外派后，目标水站将订单调解退回原归属站。
     * 仅打标记 [指定退回待确认]（即「转单中」），保留原履约站与原配送员，等待原站长决定：
     * 同意 -> 变回原站普通待分配（可重新分配/外派）；
     * 拒绝 -> 回到配送中，由原配送员继续完成配送。
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return")
    public Result<Void> directedReturn(@PathVariable Long id) {
        orderWorkflowService.directedReturn(id);
        return Result.success();
    }

    /**
     * 原归属站站长同意退回：订单变为普通待分配，可重新分配/外派
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return/approve")
    public Result<Void> directedReturnApprove(@PathVariable Long id) {
        orderWorkflowService.directedReturnApprove(id);
        return Result.success();
    }

    /**
     * 原归属站站长拒绝退回：取消转单，订单回到「配送中」，由原配送员继续完成配送
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/directed-return/reject")
    public Result<Void> directedReturnReject(@PathVariable Long id) {
        orderWorkflowService.directedReturnReject(id);
        return Result.success();
    }

    /**
     * 原归属站：被指定水站退回、等待同意的订单列表
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-returns")
    public Result<?> getDirectedReturns() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(deliveryConsoleService.listDirectedReturns(stationId));
    }

    /**
     * 目标水站视角：被其他水站指定为履约站的订单列表（首页「外派 → 指定外派」子页签）
     *
     * <p>[2026-09-18] 与抢单池同一口径：定向外派也是"站长外派"，接收站同样要在动手前
     * 一眼看到这单值多少钱、定价来自哪站、接单后钱归谁。字段与来源见
     * {@code DeliveryConsoleService} 的 {@code feeInfoOf}
     * （{@code feeStationName} / {@code settleNote} / {@code settleToMyStation} 填在 {@link Orders}
     * 的瞬时字段上，金额本来就是 {@code orders} 的列）。</p>
     *
     * <p>⚠️ <b>客户画像一并不下发</b>：这里的每一行都是别站的客户，{@code customerName} /
     * {@code customerPhone} 来自 {@code left join customer}，属于"别站的画像"，
     * 按跨站隔离口径置 null；订单的收件人与地址照常（见 {@code util/CustomerProfileMask}）。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-incoming")
    public Result<?> getDirectedIncoming() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(deliveryConsoleService.listDirectedIncoming(stationId));
    }
}
