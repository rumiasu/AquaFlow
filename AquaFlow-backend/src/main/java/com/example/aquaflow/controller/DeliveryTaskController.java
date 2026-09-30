package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.DeliveryConsoleService;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员自助面（F-18 由 {@code DeliveryController} 按读者拆出）。
 *
 * <p>本类只服务「坐在车上、手上只有一部手机的那个人」：我的任务列表（派给我的 / 配送中 /
 * 今日完成 / 历史 / 回桶记录 / 转给我的单）、订单详情、以及任务流转的写端点
 * （接单 / 确认收款 / 完成 / 拒单 / 解决 / 上报异常 / 转单 / 退回站长 / 取消申请 / 转单认领）。
 * 站长控制台面见 {@code StationDeliveryConsoleController}，跨站外派面见
 * {@code CrossStationDispatchController} —— 三者共用类级前缀 {@code /api/delivery}
 * 与 {@code DeliveryConsoleService}，<b>路径与方法一律与拆分前逐字相同</b>。</p>
 *
 * <p>⚠️ 本类<b>不注入任何 Mapper</b>（分层门禁只减不增）：一切取数走
 * {@link DeliveryConsoleService}，一切状态写入走 {@link OrderWorkflowService}。</p>
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryTaskController {

    @Autowired
    private AuditLogService auditLogService;

    /** 订单写操作唯一入口：状态/支付/桶副作用全部由它编排，Controller 不直写任何表 */
    @Autowired
    private OrderWorkflowService orderWorkflowService;

    /** 配送员现场上报要落异常单（见 reportOrder）：调用它的 recordDeliveryProblem，控制器自己不写表。 */
    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    /** 一切只读投影（列表取数、详情组装、画像掩码）都在它里面，本类不碰数据表。 */
    @Autowired
    private DeliveryConsoleService deliveryConsoleService;

    private void checkStationOwnership(Orders order) {
        // 强制当前水站非空（未绑站直接拒绝，fail-closed），并严格比对履约站
        Long myStationId = AuthContext.requireStationId();
        Long orderStation = StationUtil.deliveryStation(order);
        if (orderStation == null || !myStationId.equals(orderStation)) {
            throw new BusinessException("无权操作他站订单");
        }
    }

    private void log(String action, Long orderId, Map<String, Object> detail) {
        auditLogService.log("ORDER", action, "order:" + orderId, detail != null ? detail.toString() : "", null);
    }

    /** complete 强类型 DTO -> 原 service 期望的 Map 结构（itemReturns/returnBucketQty/barrelDiscrepancyNote/collected/note） */
    private Map<String, Object> toCompleteParams(DeliveryOrderActionDTO.Complete complete) {
        Map<String, Object> params = new HashMap<>();
        if (complete == null) return params;
        // [2026-09-16 修复] collected / note 必须显式搬过来：service 读的是 Map 里的键，
        // 少了这两行不会报任何错，只是「已收款」永远 false（现金单永远收不了款、停 已送达未付）、
        // 配送备注永远写不进 special_note。f3e702f 收敛强类型 DTO 时正是这样丢的。
        if (complete.getCollected() != null) params.put("collected", complete.getCollected());
        if (complete.getNote() != null) params.put("note", complete.getNote());
        // v43：配送员上报的楼层（选填）。**必须搬过来** —— 漏了这一行同样不会报错，
        // 只会让楼层补贴永远沿用地址楼层（"报上去也不算"，且无处可查）。
        if (complete.getReportedFloor() != null) params.put("reportedFloor", complete.getReportedFloor());
        if (complete.getItemReturns() != null) {
            List<Map<String, Object>> ir = new ArrayList<>();
            for (DeliveryOrderActionDTO.CompleteItemReturn it : complete.getItemReturns()) {
                Map<String, Object> m = new HashMap<>();
                m.put("orderItemId", it.getOrderItemId());
                m.put("actual", it.getActual());
                if (it.getProductName() != null) m.put("productName", it.getProductName());
                if (it.getExpected() != null) m.put("expected", it.getExpected());
                if (it.getReasons() != null) {
                    List<Map<String, Object>> rs = new ArrayList<>();
                    for (DeliveryOrderActionDTO.CompleteItemReturnReason r : it.getReasons()) {
                        Map<String, Object> rm = new HashMap<>();
                        if (r.getKey() != null) rm.put("key", r.getKey());
                        if (r.getQty() != null) rm.put("qty", r.getQty());
                        rs.add(rm);
                    }
                    m.put("reasons", rs);
                }
                ir.add(m);
            }
            params.put("itemReturns", ir);
        }
        if (complete.getReturnBucketQty() != null) params.put("returnBucketQty", complete.getReturnBucketQty());
        if (complete.getBarrelDiscrepancyNote() != null) params.put("barrelDiscrepancyNote", complete.getBarrelDiscrepancyNote());
        return params;
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/assigned-to-me")
    public Result<?> getAssignedToMeOrders() {
        Long staffId = AuthContext.getUserId();
        // 配送员待接单：分配给我但 status 仍为 1 的订单
        return Result.success(deliveryConsoleService.listAssignedToMe(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivering")
    public Result<?> getDeliveringOrders() {
        Long staffId = AuthContext.getUserId();
        return Result.success(deliveryConsoleService.listDelivering(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/completed-today")
    public Result<?> getCompletedToday() {
        Long staffId = AuthContext.getUserId();
        return Result.success(deliveryConsoleService.listCompletedToday(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/{id}")
    public Result<?> getOrderDetail(@PathVariable Long id) {
        Orders order = deliveryConsoleService.orderDetail(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if (AuthContext.isDelivery()) {
            checkStationOwnership(order);
            if (order.getDeliveryStaffId() != null && !order.getDeliveryStaffId().equals(AuthContext.getUserId())) {
                return Result.error("无权查看其他配送员的订单");
            }
        } else if (AuthContext.isManager()) {
            checkStationOwnership(order);
        }
        return Result.success(order);
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/accept")
    public Result<Void> acceptOrder(@PathVariable Long id) {
        orderWorkflowService.acceptOrder(id);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/confirm-offline-pay")
    public Result<Void> confirmOfflinePay(@PathVariable Long id) {
        orderWorkflowService.confirmOfflinePay(id);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/complete")
    public Result<Void> completeOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Complete complete) {
        orderWorkflowService.completeDelivery(id, toCompleteParams(complete));
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/reject/{id}")
    public Result<Void> rejectOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Reject body) {
        orderWorkflowService.rejectOrder(id, body.getReason() != null ? body.getReason() : "水站拒单");
        return Result.success();
    }

    /**
     * 解决订单：拒单并取消订单，触发退款
     * 客户需重新下单，款项原路退回
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/resolve")
    public Result<Void> resolveOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Resolve body) {
        orderWorkflowService.resolveOrder(id, body.getReason());
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/barrel-records")
    public Result<?> getBarrelRecords() {
        Long staffId = AuthContext.getUserId();
        return Result.success(deliveryConsoleService.listBarrelRecords(staffId));
    }

    /**
     * 配送异常上报：客户不接电话 / 地址找不到 / 客户拒收 / 水桶破损 / 其他。
     *
     * <p>补的是一个**前端一直在调、后端从来没实现**的端点：miniapp-delivery 的订单详情
     * 「异常反馈」按钮打的就是 {@code POST /api/delivery/orders/report/{id}}，
     * 缺路由 → 配送员每次上报都拿到「接口不存在」，现场异常没有任何留痕，站长也无从得知。</p>
     *
     * <p>边界（刻意做小）：只做「校验归属 → 写 special_note 留痕 → 通知站长」，
     * <b>不改订单状态、不动钱/票/桶账</b>。回桶差异补偿是另一条链路
     * （{@code order_barrel_exception} + 站长审批），不要在这里混。</p>
     *
     * <p>⚠️ [2026-09-27 改] 带 {@code reasonKey} 时**同时落一条异常单**（{@code STAFF_RECORDED}，
     * 进站长「待处置」）。产品口径原话：「配送遇到问题，不应该是异常单处理吗，为什么会是转让处理」——
     * 在这之前配送员上报现场问题只写一行备注，站长端「异常订单」里永远是"少回桶/多回桶"，
     * "破损/拒收"这类必须处置的问题只能等站长自己翻到那条备注再手工补录
     * （配送员能识别问题，系统却不收"问题是什么"）。类别由服务端按 reasonKey 映射，
     * 客户端**不传类别**；省略 reasonKey 时保持旧行为（只留痕），老客户端不会被这次改动打断。</p>
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/report/{id}")
    public Result<Void> reportOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Report body) {
        Orders order = deliveryConsoleService.findOrder(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        checkStationOwnership(order);
        // 配送员只能上报自己名下的单（站长不受限，用于代报）
        if (AuthContext.isDelivery()) {
            Long staffId = AuthContext.getUserId();
            if (order.getDeliveryStaffId() == null || !order.getDeliveryStaffId().equals(staffId)) {
                return Result.error("仅可上报分配给自己的订单");
            }
        }

        String reason = body.getReason().trim();
        if (reason.isEmpty() || reason.length() > 50) {
            return Result.error("异常原因长度需在 1-50 字之间");
        }
        Long staffId = AuthContext.getUserId();

        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", id);
        detail.put("reason", reason);
        detail.put("reasonKey", body.getReasonKey());
        detail.put("staffId", staffId);
        log("REPORT_EXCEPTION", id, detail);

        // 留痕 + 建异常单**一起交给 service 的同一个事务**（见方法上的说明）：非法 reasonKey 会让它
        // 抛业务异常（code=1 可读文案），此时备注与异常单一起回滚，不留半成品。
        // 控制器不写库、也不开事务 —— 与全仓其它 controller 的形状一致。
        orderBarrelExceptionService.recordDeliveryProblem(id, body.getReasonKey(),
                body.getNote(), "[配送异常] " + reason + "（上报人ID=" + staffId + "）");

        // 【为什么这里没有推送站长】NotificationService 的 6 个方法目前**全是空壳**：
        // 只按站长列表打日志，拼好的 message 从未发出（其余 5 个方法连调用方都没有，
        // OrderBarrelExceptionServiceImpl 里那一处也是注释掉的）。
        // 在这里调 pushBatchSummary 只会得到一行日志，却让人误以为"已经通知站长了" ——
        // 与其做这种假动作，不如把事实写清楚：
        //   现状：配送异常落在 orders.special_note、audit_log，以及一条 STAFF_RECORDED 异常单；
        //   站长可见性：异常单会让站长端「待处理桶异常」的待办数字 +1、异常列表多一条
        //               （产品裁定：本次不做真推送，靠列表/待办数字暴露）；
        //   待办：接入真实推送渠道（微信订阅消息）后，在这里补一次真正的通知。
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}")
    public Result<Void> transferOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Transfer body) {
        String reason = body.getReason() != null ? body.getReason() : "配送员转让";
        orderWorkflowService.transferToStaff(id, body.getDeliveryStaffId(), reason);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/return/{id}")
    public Result<Void> returnToStation(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.ReturnToStation body) {
        orderWorkflowService.returnToStation(id, body.getReason() != null ? body.getReason() : "配送员退回站长");
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/cancel")
    public Result<Void> cancelTransfer(@PathVariable Long id) {
        orderWorkflowService.cancelTransfer(id);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/claim")
    public Result<Void> claimTransfer(@PathVariable Long id) {
        orderWorkflowService.claimTransfer(id);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/transfer/{id}/reject")
    public Result<Void> rejectTransfer(@PathVariable Long id) {
        orderWorkflowService.rejectTransfer(id);
        return Result.success();
    }

    /** 配送员发起取消申请：订单已被接单，取消需站长同意（body 可为空，reason 可选）。 */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/cancel-request")
    public Result<Void> requestCancel(@PathVariable Long id,
                                      @RequestBody(required = false) DeliveryOrderActionDTO.Reject body) {
        orderWorkflowService.requestCancelByStaff(id, body != null ? body.getReason() : null);
        return Result.success();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/stats/today")
    public Result<Map<String, Object>> getTodayStats() {
        return Result.success(deliveryConsoleService.todayStats(AuthContext.getUserId(), AuthContext.getStationId()));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/history")
    public Result<?> getDeliveryHistory() {
        Long staffId = AuthContext.getUserId();
        return Result.success(deliveryConsoleService.listHistory(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers/incoming")
    public Result<?> getIncomingTransfers() {
        Long staffId = AuthContext.getUserId();
        return Result.success(deliveryConsoleService.listIncomingTransfers(staffId));
    }
}
