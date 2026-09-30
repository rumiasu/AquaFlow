package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.service.DeliveryConsoleService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 站长控制台面（F-18 由 {@code DeliveryController} 按读者拆出）。
 *
 * <p>本类只服务「坐在电脑前、要看整站的那位站长」：待分配 / 配送中 / 已完成 / 转单 / 退回 /
 * 已送达未收款 / 跨站履约汇总 / 待审批，以及分配、审批退回、审批取消申请、站长拒单这些写端点。
 * 配送员自助面见 {@code DeliveryTaskController}，跨站外派面见
 * {@code CrossStationDispatchController} —— 三者共用类级前缀 {@code /api/delivery}
 * 与 {@code DeliveryConsoleService}，<b>路径与方法一律与拆分前逐字相同</b>。</p>
 *
 * <p>⚠️ 本类<b>不注入任何 Mapper</b>（分层门禁只减不增）：一切取数走
 * {@link DeliveryConsoleService}，一切状态写入走 {@link OrderWorkflowService}。</p>
 */
@RestController
@RequestMapping("/api/delivery")
public class StationDeliveryConsoleController {

    @Autowired
    private DeliveryConsoleService deliveryConsoleService;

    @Autowired
    private OrderWorkflowService orderWorkflowService;

    /**
     * 本站**还没分配配送员**的待配送单。
     *
     * <p>⚠️ [2026-09-26 产品裁定] <b>只有站长能读</b>：产品原话「如果是未分配的订单，不应该直接
     * 显示给配送员吧 —— 现在站长还没分配，刚同意入站就能看见订单了，就能接单了」。
     * 配送员那一侧只保留 {@code /orders/assigned-to-me}（派给我的），别再让配送员的页面合并这一支 ——
     * 那会让刚通过绑定的配送员立刻看到全站未分配的单并直接接走，站长的"分配"这一步等于不存在。</p>
     *
     * <p>⚠️ 与接单闸门（{@code OrderWorkflowServiceImpl.acceptOrder}）必须一致：列表看不到、
     * 但拿 id 编造去接单同样要被拒（本仓"列表看不到但 id 可编造"的老坑）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/pending")
    public Result<?> getPendingOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listPendingByStation(stationId));
    }

    /**
     * 站长待分配列表。
     *
     * <p>⚠️ 这张列表里<b>混着"他站定向外派给本站"的单</b>（SQL 的 {@code o.delivery_station_id = 本站}
     * 那一支）：它们带的是<b>归属站</b>客户的姓名/手机号。若不一并抹掉，刚在抢单池 / 指定外派两个
     * 端点上堵住的画像泄露，换个端点（{@code GET /orders/station-pending}）就原样漏出来。
     * 本站自己的单保持原样 —— 那是本站客户的画像，站长本来就该看到
     * （口径与唯一实现见 {@code util/CustomerProfileMask}）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-pending")
    public Result<?> getStationPendingOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationPendingUnassigned(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-delivering")
    public Result<?> getStationDeliveringOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationByStatus(stationId, OrderStatus.DELIVERING));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-completed")
    public Result<?> getStationCompletedOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationByStatus(stationId, OrderStatus.COMPLETED));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-transfer")
    public Result<?> getStationTransferOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationTransferred(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-return")
    public Result<?> getStationReturnOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationReturn(stationId));
    }

    // [2026-09-18 删除] GET /orders/station-exception：名字叫"异常"、实际返回 status=5 的**取消单**，
    // 与 GET /api/orders?status=5 重复，且两端小程序都没调用（docs/audit/history/review/2026-09-16-死端点评估.md 判"删除"，已执行）。
    // 站长的待办/外派等查询用 /orders/station-pending、/orders/dispatch-tracking 等既有端点。
    // 回归：ManagerOrderControllerRemovedIntegrationTest 断言该路径返回 404。

    /**
     * 跨站履约单（本站是履约站、归属站是别站）—— 站长端「订单」页**归并成一行**展示。
     *
     * <p>产品口径（2026-09-18）：「跨站单订单可以算，只是不能看用户画像，但是可以把跨站单
     * **统一成一个**，统一看接了多少跨站单。」所以这里一次返回：总数、金额合计、按状态分类计数，
     * 以及逐单明细（**已过画像掩码**）—— 页面默认只显示那一行汇总，点开才看明细，
     * 免得列表里出现一堆"无名订单"。</p>
     *
     * <p>⚠️ 与「站长端订单列表」是两套口径，别合并：那张列表按**归属站**取数（只列本站自己的单，
     * 走 {@code GET /api/orders}）；本端点按**履约站**取数（本站接下的别站单）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/cross-station")
    public Result<?> getCrossStationOrders() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(deliveryConsoleService.crossStationSummary(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivered-unpaid")
    public Result<?> getDeliveredUnpaid() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationByStatus(stationId, OrderStatus.DELIVERED));
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/assign/{id}")
    public Result<Void> assignOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Assign body) {
        // riskAcknowledged：**接收站**对押金/桶权益风险的二次确认（他站定向外派给本站的单）。
        // 漏搬这个字段就会重演 §8.15「请求体收敛成强类型 DTO 后静默丢字段」——前端一直在发、后端当没看见。
        orderWorkflowService.assignToStaff(id, body.getDeliveryStaffId(),
                Boolean.TRUE.equals(body.getRiskAcknowledged()));
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return/{id}/approve")
    public Result<Void> approveReturn(@PathVariable Long id) {
        orderWorkflowService.approveReturn(id);
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/return/{id}/reject")
    public Result<Void> rejectReturn(@PathVariable Long id) {
        orderWorkflowService.rejectReturn(id);
        return Result.success();
    }

    /** 站长同意取消申请：走完整退款链（退水票/支付/押金、回补库存）并置订单为已取消。 */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/cancel-request/{id}/approve")
    public Result<Void> approveCancelRequest(@PathVariable Long id) {
        orderWorkflowService.approveCancelRequest(id);
        return Result.success();
    }

    /** 站长驳回取消申请：订单保持原状态，由原配送员继续履约。 */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/cancel-request/{id}/reject")
    public Result<Void> rejectCancelRequest(@PathVariable Long id) {
        orderWorkflowService.rejectCancelRequest(id);
        return Result.success();
    }

    /**
     * 站长「审批」页数据：按发起通道分列。
     * <p>{@code customer} = 客户发起的取消申请；{@code station} = 站内（配送员）发起的
     * 退回站长/转让/重分配/取消申请。两者合并为站长端「审批」大页签下的两个子页签。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/pending-approvals")
    public Result<Map<String, Object>> getPendingApprovals() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.pendingApprovals(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers")
    public Result<?> getTransferRecords() {
        Long stationId = AuthContext.getStationId();
        return Result.success(deliveryConsoleService.listStationTransferred(stationId));
    }

    /**
     * 站长拒单（简化版）：直接取消 或 尝试外派进入抢单池
     */
    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/{id}/station-reject")
    public Result<Void> stationReject(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.StationReject body) {
        String reason = body.getReason() != null ? body.getReason() : "站长拒单";
        boolean tryDispatch = Boolean.TRUE.equals(body.getTryDispatch());
        orderWorkflowService.stationReject(id, reason, tryDispatch);
        return Result.success();
    }
}
