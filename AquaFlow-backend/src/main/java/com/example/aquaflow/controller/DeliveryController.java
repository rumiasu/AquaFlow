package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员订单接口
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private AuditLogService auditLogService;

    /** 订单写操作唯一入口：状态/支付/桶副作用全部由它编排，Controller 不再直写任何表 */
    @Autowired
    private OrderWorkflowService orderWorkflowService;

    // 注：本类曾直接注入 OrderTransferMapper（转单状态）与 BarrelLedgerService（桶权益总账）直写那两张表，
    // 已按「Controller 只做认证 + 调服务 + 包 Result，不得触碰业务表」的契约全部移入 OrderWorkflowService。
    // 新增写操作请调 orderWorkflowService，**不要在本类重新注入 Mapper** —— 那会绕开状态机与账本写入口。
    private void checkStationOwnership(Orders order) {
        // 强制当前水站非空（未绑站直接拒绝，fail-closed），并严格比对履约站
        Long myStationId = AuthContext.requireStationId();
        Long orderStation = deliveryStation(order);
        if (orderStation == null || !myStationId.equals(orderStation)) {
            throw new BusinessException("无权操作他站订单");
        }
    }

    private void log(String action, Long orderId, Map<String, Object> detail) {
        auditLogService.log("ORDER", action, "order:" + orderId, detail != null ? detail.toString() : "", null);
    }

    /** complete 强类型 DTO -> 原 service 期望的 Map 结构（itemReturns/returnBucketQty/barrelDiscrepancyNote） */
    private Map<String, Object> toCompleteParams(DeliveryOrderActionDTO.Complete complete) {
        Map<String, Object> params = new HashMap<>();
        if (complete == null) return params;
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



    private Long deliveryStation(Orders o) {
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/pending")
    public Result<?> getPendingOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listPendingByStationId(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/assigned-to-me")
    public Result<?> getAssignedToMeOrders() {
        Long staffId = AuthContext.getUserId();
        // 配送员待接单：分配给我但 status 仍为 1 的订单
        return Result.success(orderMapper.listAssignedToStaff(staffId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivering")
    public Result<?> getDeliveringOrders() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/completed-today")
    public Result<?> getCompletedToday() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listByDeliveryStaffIdAndDate(staffId, OrderStatus.COMPLETED, java.time.LocalDate.now()));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-pending")
    public Result<?> getStationPendingOrders() {
        Long stationId = AuthContext.getStationId();
        // 只返回未分配配送员的待分配订单
        return Result.success(orderMapper.listStationPendingUnassigned(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-delivering")
    public Result<?> getStationDeliveringOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.DELIVERING));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-completed")
    public Result<?> getStationCompletedOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.COMPLETED));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-transfer")
    public Result<?> getStationTransferOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listTransferredOrders(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-return")
    public Result<?> getStationReturnOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listStationReturnOrders(stationId));
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-exception")
    public Result<?> getStationExceptionOrders() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listStationExceptionOrders(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/{id}")
    public Result<?> getOrderDetail(@PathVariable Long id) {
        Orders order = orderMapper.getById(id);
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
        order.setItems(orderItemMapper.listByOrderId(id));
        // 「待我确认的转单」由后端按登录人判定（前端此前读的 isTransferTarget 后端并不存在）
        order.setTransferTarget(isTransferTarget(order));
        return Result.success(order);
    }

    /**
     * 判定订单是否为「转给当前登录人、待其确认」的转单。
     * <p>站间指定退回 -> 归属站站长决策；配送员转单 -> 本站站长可决策，
     * 或未分配/已分配给本人的配送员可认领（与 claimTransfer 的校验口径保持一致）。</p>
     */
    private boolean isTransferTarget(Orders order) {
        if (!order.getTransferPending()) return false;
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();
        if ("DIRECTED".equals(order.getTransferKind())) {
            return stationId != null && stationId.equals(order.getStationId());
        }
        if (stationId != null && stationId.equals(deliveryStation(order))) return true;
        return order.getDeliveryStaffId() == null
                || (staffId != null && staffId.equals(order.getDeliveryStaffId()));
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
    @GetMapping("/orders/delivered-unpaid")
    public Result<?> getDeliveredUnpaid() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listByStationIdAndStatus(stationId, OrderStatus.DELIVERED));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/reject/{id}")
    public Result<Void> rejectOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Reject body) {
        orderWorkflowService.rejectOrder(id, body.getReason() != null ? body.getReason() : "水站拒单");
        return Result.success();
    }

    /**
     * 外派订单：临时指派给其他水站配送，客户归属不变
     * 仅修改 delivery_station_id，owner_station_id 保持不变
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/dispatch")
    public Result<Void> dispatchOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Dispatch body) {
        Long targetStationId = body.getTargetStationId();
        String reason = body.getReason() != null ? body.getReason() : "外派配送";
        orderWorkflowService.dispatchExternal(id, targetStationId, reason);
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
        return Result.success(orderMapper.listBarrelRecords(staffId));
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
     */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/report/{id}")
    public Result<Void> reportOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Report body) {
        Orders order = orderMapper.getById(id);
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

        orderMapper.appendSpecialNote(id, "[配送异常] " + reason + "（上报人ID=" + staffId + "）");

        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", id);
        detail.put("reason", reason);
        detail.put("staffId", staffId);
        log("REPORT_EXCEPTION", id, detail);

        // 【为什么这里没有推送站长】NotificationService 的 6 个方法目前**全是空壳**：
        // 只按站长列表打日志，拼好的 message 从未发出（其余 5 个方法连调用方都没有，
        // OrderBarrelExceptionServiceImpl 里那一处也是注释掉的）。
        // 在这里调 pushBatchSummary 只会得到一行日志，却让人误以为"已经通知站长了" ——
        // 与其做这种假动作，不如把事实写清楚：
        //   现状：配送异常只落在 orders.special_note 与 audit_log 里；
        //   影响：站长不会主动收到提醒，需要自己翻订单详情才能看到；
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

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/assign/{id}")
    public Result<Void> assignOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Assign body) {
        orderWorkflowService.assignToStaff(id, body.getDeliveryStaffId());
        return Result.success();
    }

    @RequireRole("STATION_MANAGER")
    @PostMapping("/orders/transfer/{id}/outsource")
    public Result<Void> outsourceOrder(@PathVariable Long id, @RequestBody @Valid DeliveryOrderActionDTO.Outsource body) {
        String reason = body.getReason() != null ? body.getReason() : "站长指定水站外派";
        orderWorkflowService.outsource(id, body.getTargetStationId(), reason);
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

    /* ========== 取消申请（已接单订单的取消须站长审批，2026-09-14） ========== */

    /** 配送员发起取消申请：订单已被接单，取消需站长同意（body 可为空，reason 可选）。 */
    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @PostMapping("/orders/{id}/cancel-request")
    public Result<Void> requestCancel(@PathVariable Long id,
                                      @RequestBody(required = false) DeliveryOrderActionDTO.Reject body) {
        orderWorkflowService.requestCancelByStaff(id, body != null ? body.getReason() : null);
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
        Map<String, Object> data = new HashMap<>();
        data.put("customer", orderMapper.listPendingCustomerCancelRequests(stationId));
        data.put("station", orderMapper.listTransferredOrders(stationId));
        return Result.success(data);
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/stats/today")
    public Result<Map<String, Object>> getTodayStats() {
        Long staffId = AuthContext.getUserId();
        Long stationId = AuthContext.getStationId();

        Map<String, Object> stats = new HashMap<>();
        var completed = orderMapper.listByDeliveryStaffIdAndDate(staffId, OrderStatus.COMPLETED, java.time.LocalDate.now());
        stats.put("completedCount", completed.size());
        var delivering = orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING);
        stats.put("deliveringCount", delivering.size());
        var pending = orderMapper.list(stationId, null, OrderStatus.PENDING, null, null, null, null);
        stats.put("pendingCount", pending.size());
        int totalReturn = completed.stream()
                .mapToInt(o -> o.getReturnBucketQty() != null ? o.getReturnBucketQty() : 0)
                .sum();
        stats.put("returnBarrels", totalReturn);
        // 待收款订单数：以前端读 stats.unpaidOrders，但后端从未下发该字段，看板恒显示 0。
        // 改为后端按 payment_status / payment_method 真实统计（水票视同已付，不计入）。
        stats.put("unpaidOrders", stationId == null ? 0 : orderMapper.countUncollected(stationId));

        return Result.success(stats);
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/history")
    public Result<?> getDeliveryHistory() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listHistoryByDeliveryStaffId(staffId, OrderStatus.COMPLETED));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers")
    public Result<?> getTransferRecords() {
        Long stationId = AuthContext.getStationId();
        return Result.success(orderMapper.listTransferredOrders(stationId));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/transfers/incoming")
    public Result<?> getIncomingTransfers() {
        Long staffId = AuthContext.getUserId();
        return Result.success(orderMapper.listIncomingTransfers(staffId));
    }

    // ==================== 抢单池 & 外派追踪 ====================

    /**
     * 抢单池列表：获取同城市+距离范围内外派订单
     * 仅返回 delivery_station_id IS NULL 的订单
     * 包含商品匹配信息（辅助提示，不硬拦截）
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/pool")
    public Result<?> getPoolOrders() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> poolOrders = orderMapper.listPoolOrders(stationId);

        // 获取本站所有商品（用于匹配）
        List<com.example.aquaflow.entity.Product> stationProducts =
                productMapper.listByStationId(stationId);

        // 为每个订单计算商品匹配结果
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Orders order : poolOrders) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("id", order.getId());
            orderData.put("customerName", order.getCustomerName());
            orderData.put("customerPhone", order.getCustomerPhone());
            orderData.put("receiverName", order.getReceiverName());
            orderData.put("receiverPhone", order.getReceiverPhone());
            orderData.put("addressDetail", order.getAddressDetail());
            orderData.put("addressSnapshot", order.getAddressSnapshot());
            orderData.put("quantity", order.getQuantity());
            orderData.put("createTime", order.getCreateTime());

            // 从 order_item 获取商品名称（一单可能有多商品，取第一个）
            String orderProductName = "";
            List<com.example.aquaflow.entity.OrderItem> items = order.getItems();
            if (items != null && !items.isEmpty()) {
                orderProductName = items.get(0).getProductNameSnapshot();
                orderData.put("productName", orderProductName);
            }

            // 商品匹配
            Map<String, String> matchResult = checkProductMatch(orderProductName, stationProducts);
            orderData.put("productMatch", matchResult);

            result.add(orderData);
        }

        return Result.success(result);
    }

    /**
     * 商品匹配算法：提取品牌关键词进行模糊匹配
     * 返回：{ level: "full"/"partial"/"none", matchName: "匹配的商品名", hint: "提示文案" }
     */
    private Map<String, String> checkProductMatch(String orderProductName,
                                                   List<com.example.aquaflow.entity.Product> stationProducts) {
        Map<String, String> result = new HashMap<>();
        if (orderProductName == null || orderProductName.isEmpty()) {
            result.put("level", "none");
            result.put("hint", "未找到匹配商品");
            return result;
        }

        String orderBrand = extractBrand(orderProductName);

        // 精确匹配
        for (com.example.aquaflow.entity.Product p : stationProducts) {
            String stationBrand = extractBrand(p.getName());
            if (orderBrand.equals(stationBrand) || orderBrand.contains(stationBrand) || stationBrand.contains(orderBrand)) {
                result.put("level", "full");
                result.put("matchName", p.getName());
                result.put("hint", "高度匹配");
                return result;
            }
        }

        // 检查是否有同类商品（如都是"桶装水"）
        for (com.example.aquaflow.entity.Product p : stationProducts) {
            String stationName = p.getName() != null ? p.getName() : "";
            // 检查是否都包含"桶装水"、"矿泉水"、"纯净水"等关键词
            if (isSameCategory(orderProductName, stationName)) {
                result.put("level", "partial");
                result.put("matchName", p.getName());
                result.put("hint", "疑似匹配，请确认库存");
                return result;
            }
        }

        result.put("level", "none");
        result.put("hint", "未找到匹配商品");
        return result;
    }

    /**
     * 品牌关键词提取：去掉规格、包装等信息
     * "农夫山泉纯净水 550ml×24" → "农夫山泉"
     */
    private String extractBrand(String productName) {
        if (productName == null) return "";
        return productName
                .replaceAll("\\d+[mMlL升L]{1,2}", "")      // 移除容量 550ml, 1.5L
                .replaceAll("\\d+×\\d+", "")                // 移除包装 24×1
                .replaceAll("\\d+\\*\\d+", "")              // 移除包装 24*1
                .replaceAll("(桶装|瓶装|整箱|大桶|小桶)", "") // 移除包装词
                .replaceAll("(纯净水|矿泉水|天然水|饮用水|山泉水|水)", "") // 移除水类型
                .replaceAll("\\s+", "")                      // 移除空格
                .trim();
    }

    /**
     * 判断两个商品是否属于同类（如都是桶装水）
     */
    private boolean isSameCategory(String name1, String name2) {
        String[] categories = {"桶装水", "矿泉水", "纯净水", "天然水", "饮用水", "山泉水"};
        for (String cat : categories) {
            if (name1.contains(cat) && name2.contains(cat)) {
                return true;
            }
        }
        return false;
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
     * 外派追踪列表：本站外派出去的订单状态
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/dispatch-tracking")
    public Result<?> getDispatchTracking() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> dispatched = orderMapper.listDispatchedOrders(stationId);
        return Result.success(dispatched);
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
        return Result.success(orderMapper.listDirectedReturns(stationId));
    }

    /**
     * 目标水站视角：被其他水站指定为履约站的订单列表（他站外派给我）
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-incoming")
    public Result<?> getDirectedIncoming() {
        Long stationId = AuthContext.requireStationId();
        return Result.success(orderMapper.listDirectedIncoming(stationId));
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
