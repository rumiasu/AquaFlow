package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.AuditLogService;
import com.example.aquaflow.service.CustomerRiskService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.dto.DeliveryOrderActionDTO;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CustomerProfileMask;
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

    /** 抢单池/他站外派要下发「定价来源站名」，站名从这里取（见 loadStationNames 的批量做法）。 */
    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private AuditLogService auditLogService;

    /** 订单写操作唯一入口：状态/支付/桶副作用全部由它编排，Controller 不再直写任何表 */
    @Autowired
    private OrderWorkflowService orderWorkflowService;

    @Autowired
    private AddressMapper addressMapper;

    /**
     * 给站长端订单列表打**客户信用标记**（黄=有挂账 / 红=逾期或超额度）。
     *
     * <p>它只读、只算，不写任何业务表 —— 所以不违反下面那条"不要重新注入 Mapper"的契约。
     * 判据只有一份实现（{@code CustomerRiskService.summarizeStation}），本类不再抄一遍。</p>
     */
    @Autowired
    private CustomerRiskService customerRiskService;

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



    private Long deliveryStation(Orders o) {
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    /**
     * 抹掉一批订单里**跨站行**的客户画像字段 —— 口径与唯一实现见 {@link CustomerProfileMask}。
     *
     * <p>用在「同一张表里混着本站单与他站履约单」的列表上：只抹跨站行，本站自己的单照常显示客户姓名
     * （那是本站客户，站长与配送员本来就该看到）。**整表都是别站客户**的列表
     * （抢单池 / 他站外派给我）不走这里，它们在各自端点里无条件置 null。</p>
     *
     * <p>⚠️ 配送员侧的列表也要过这一道：跨站单一旦被抢单/接收，它就以
     * {@code delivery_staff_id = 本站配送员} 的形态出现在任务、历史、回桶记录里 ——
     * 只在池子与外派两个入口堵，等于"认领前看不到、认领后就看到了"。</p>
     */
    private List<Orders> maskCrossStationProfiles(List<Orders> rows) {
        if (rows != null) {
            for (Orders o : rows) {
                CustomerProfileMask.maskIfCrossStation(o);
            }
        }
        return rows;
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
        return Result.success(maskCrossStationProfiles(orderMapper.listAssignedToStaff(staffId)));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/delivering")
    public Result<?> getDeliveringOrders() {
        Long staffId = AuthContext.getUserId();
        return Result.success(maskCrossStationProfiles(
                orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING)));
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/orders/completed-today")
    public Result<?> getCompletedToday() {
        Long staffId = AuthContext.getUserId();
        return Result.success(maskCrossStationProfiles(orderMapper.listByDeliveryStaffIdAndDate(
                staffId, OrderStatus.COMPLETED, java.time.LocalDate.now())));
    }

    /**
     * 站长待分配列表。
     *
     * <p>⚠️ 这张列表里<b>混着"他站定向外派给本站"的单</b>（SQL 的 {@code o.delivery_station_id = 本站}
     * 那一支）：它们带的是<b>归属站</b>客户的姓名/手机号。若不一并抹掉，刚在抢单池 / 他站外派两个
     * 端点上堵住的画像泄露，换个端点（{@code GET /orders/station-pending}）就原样漏出来。
     * 本站自己的单保持原样 —— 那是本站客户的画像，站长本来就该看到
     * （口径与唯一实现见 {@link CustomerProfileMask}）。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/orders/station-pending")
    public Result<?> getStationPendingOrders() {
        Long stationId = AuthContext.getStationId();
        // 只返回未分配配送员的待分配订单
        List<Orders> rows = orderMapper.listStationPendingUnassigned(stationId);
        maskCrossStationProfiles(rows);
        // [2026-09-21] 给每行附上**客户信用标记**，站长端据此上色：
        // 黄 = 有挂账；红 = 已逾期或**超额度**（2026-09-22 起列表也算额度，
        // 与客户详情页的等级逐字同源，见 CustomerRiskService.levelOf）。
        attachCustomerRisk(stationId, rows);
        return Result.success(rows);
    }

    /**
     * 给一批订单附上"这个客户欠不欠钱、是不是企业"—— 站长端列表据此上色。
     *
     * <p>⚠️ <b>一次批量查，不要逐行查</b>：列表可能有几十行，逐行算风险就是几十次 SQL
     * （本仓"列表页 N+1"的老坑）。判据本身只有一份实现，在 {@code CustomerRiskService.summarizeStation}。</p>
     *
     * <p>⚠️ 这些是**本站**的待分配单，不受 {@link CustomerProfileMask}（跨站不下发画像）影响；
     * 但反过来，**跨站外派 / 抢单池那两张列表绝不能调本方法** ——
     * "这个客户欠多少钱"是归属站的经营信息，下发给别站就是跨租户泄露（AGENTS §1.1 的可见面）。</p>
     */
    private void attachCustomerRisk(Long stationId, List<Orders> rows) {
        if (stationId == null || rows == null || rows.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> summary = customerRiskService.summarizeStation(stationId);
        for (Orders o : rows) {
            if (o.getCustomerId() == null) {
                continue;
            }
            Map<String, Object> s = summary.get(o.getCustomerId());
            if (s == null) {
                // 该客户在本站没有未结赊账 —— 明确置成"正常"，别留 null 让前端猜
                o.setCustomerRiskLevel(CustomerRiskService.NORMAL);
                o.setCustomerRiskLevelText(CustomerRiskService.textOf(CustomerRiskService.NORMAL));
                o.setCustomerRiskNote("没有未结欠款");
                o.setOutstandingCredit(java.math.BigDecimal.ZERO);
                o.setOverdueDays(0);
                continue;
            }
            o.setCustomerRiskLevel(String.valueOf(s.get("level")));
            // 徽标文案与那句话都直接用服务端算好的，**前端不自己拼也不自带映射表**
            // （同一句"欠了多少 / 逾期几天"若前端再拼一次，列表与详情迟早说成两句不同的话）。
            o.setCustomerRiskLevelText(String.valueOf(s.get("levelText")));
            o.setCustomerRiskNote(String.valueOf(s.get("note")));
            o.setOutstandingCredit((java.math.BigDecimal) s.get("outstandingCredit"));
            o.setOverdueDays((Integer) s.get("overdueDays"));
        }
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

    // [2026-09-18 删除] GET /orders/station-exception：名字叫"异常"、实际返回 status=5 的**取消单**，
    // 与 GET /api/orders?status=5 重复，且两端小程序都没调用（docs/audit/2026-09-16-死端点评估.md 判"删除"，已执行）。
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
        List<Orders> rows = maskCrossStationProfiles(orderMapper.listCrossStationOrders(stationId));
        java.math.BigDecimal amountTotal = java.math.BigDecimal.ZERO;
        int pending = 0;
        int delivering = 0;
        int done = 0;
        if (rows != null) {
            for (Orders o : rows) {
                if (o.getTotalAmount() != null) {
                    amountTotal = amountTotal.add(o.getTotalAmount());
                }
                int st = o.getStatus() != null ? o.getStatus() : 0;
                if (st == OrderStatus.PENDING) {
                    pending++;
                } else if (st == OrderStatus.DELIVERING || st == OrderStatus.DELIVERED) {
                    delivering++;
                } else if (st == OrderStatus.COMPLETED) {
                    done++;
                }
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("count", rows == null ? 0 : rows.size());
        data.put("amountTotal", amountTotal);
        data.put("pending", pending);
        data.put("delivering", delivering);
        data.put("completed", done);
        data.put("orders", rows == null ? new ArrayList<>() : rows);
        return Result.success(data);
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
        // 楼层 / 电梯：送货的人要知道这一单要不要上楼。
        // orderMapper.getById 是纯 orders 查询（不带 address 关联），所以在这里补一次读；
        // 取的是**当前地址**的值而不是下单快照 —— 详见 Orders.addressFloor 的字段注释。
        if (order.getAddressId() != null) {
            Address addr = addressMapper.getById(order.getAddressId());
            if (addr != null) {
                order.setAddressFloor(addr.getFloor());
                order.setAddressHasElevator(addr.getHasElevator());
            }
        }
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
     * 他站外派 / 待分配 / 外派追踪是实体直出），而风险文案的判据与文案都只该在服务端有一份
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
        Orders order = orderMapper.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        Long stationId = AuthContext.requireStationId();
        boolean mine = stationId.equals(deliveryStation(order)) || stationId.equals(order.getStationId());
        if (!mine) {
            return Result.error("无权查看他站订单");
        }
        String note = orderWorkflowService.crossStationRiskNote(order);
        Map<String, Object> data = new HashMap<>();
        data.put("depositBarrelRisk", note != null);
        data.put("riskNote", note);
        return Result.success(data);
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
        return Result.success(maskCrossStationProfiles(orderMapper.listBarrelRecords(staffId)));
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
        // riskAcknowledged：**接收站**对押金/桶权益风险的二次确认（他站定向外派给本站的单）。
        // 漏搬这个字段就会重演 §8.15「请求体收敛成强类型 DTO 后静默丢字段」——前端一直在发、后端当没看见。
        orderWorkflowService.assignToStaff(id, body.getDeliveryStaffId(),
                Boolean.TRUE.equals(body.getRiskAcknowledged()));
        return Result.success();
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
        int totalReturn = completed.stream()
                .mapToInt(o -> o.getReturnBucketQty() != null ? o.getReturnBucketQty() : 0)
                .sum();
        stats.put("returnBarrels", totalReturn);
        // 待收款订单数：以前端读 stats.unpaidOrders，但后端从未下发该字段，看板恒显示 0。
        // 改为后端按 payment_status / payment_method 真实统计（水票视同已付，不计入）。
        // ⚠️ 页面侧目前没有消费方（「我的」那三个数是按角色取的），但**不能删**：
        // `OrderSettleStationIntegrationTest.unpaidCount()` 拿它当"待收款口径"的探针（4 处断言）。
        stats.put("unpaidOrders", stationId == null ? 0 : orderMapper.countUncollected(stationId));
        // [2026-09-19 删除] pendingCount（本站 status=1 的单数）：
        // 唯一消费方是「我的」页的「待配送」格，该格已在统计卡按角色分叉时撤掉（配送页本身就是那个页签）。
        // 它每次都要跑一遍 listStationPending 只为了取 .size()，而且**站级口径混在一个按人统计的响应里**，
        // 正是口径混淆的温床。证据与核实过程见 docs/audit/2026-09-16-死端点评估.md「删除登记表」#9。

        return Result.success(stats);
    }

    @RequireRole({"DELIVERY", "STATION_MANAGER"})
    @GetMapping("/history")
    public Result<?> getDeliveryHistory() {
        Long staffId = AuthContext.getUserId();
        return Result.success(maskCrossStationProfiles(
                orderMapper.listHistoryByDeliveryStaffId(staffId, OrderStatus.COMPLETED)));
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
        // 与待接单同一口径：转给我的单可能是跨站履约单（配送员之间转手也能转到它）
        return Result.success(maskCrossStationProfiles(orderMapper.listIncomingTransfers(staffId)));
    }

    // ==================== 抢单池 & 外派追踪 ====================

    /**
     * 抢单池列表：获取同城市+距离范围内外派订单
     * 仅返回 delivery_station_id IS NULL 的订单
     * 包含商品匹配信息（辅助提示，不硬拦截）
     *
     * <p>[2026-09-18] 站长的产品裁定：「配送费是站长说了算，跨站外派仍按<b>本站（外派方）</b>定价，
     * 但钱去向实际配送的履约站，而且<b>抢单前要一眼看到</b>」。所以本列表在原有的地址/数量之外
     * 追加下发四项<b>金额与去向</b>信息（{@link #attachFeeInfo}）：订单上的费用快照、定价来源站名、
     * 结算去向文案、钱是否计入本站。</p>
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
     * 这里显式置 {@code null}。订单自身的配送信息（{@code receiverName} / {@code receiverPhone} /
     * {@code addressDetail} / {@code addressSnapshot}）与商品金额<b>照常下发</b>：
     * 那些是"订单有关的信息"，跨站配送必需。同一口径见 {@link #getDirectedIncoming}。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/pool")
    public Result<?> getPoolOrders() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> poolOrders = orderMapper.listPoolOrders(stationId);

        // 获取本站所有商品（用于匹配）
        List<com.example.aquaflow.entity.Product> stationProducts =
                productMapper.listByStationId(stationId);

        // 站名映射：**一次查全表**再按 id 取，不按订单逐条查 station（池里 N 单就有 N 次查询，
        // 而 station 是小表：一次 listAll 的成本远低于 N 次 getById）。见 loadStationNames。
        Map<Long, String> stationNames = loadStationNames();

        // 为每个订单计算商品匹配结果
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Orders order : poolOrders) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("id", order.getId());
            // 客户画像（姓名/手机号）**刻意不给**：池子是跨租户可见面，这些值来自 `left join customer`，
            // 是**归属站**的客户画像。这里选择「保留键、显式置 null」而不是「不 put」：
            // ① 另一个列表 /orders/directed-incoming 直接下发实体、只能靠置 null 表达"后端不给"，
            //    两处保持同一形状（键在、值为 null），前端对同一种情况只需一套判断；
            // ② 键仍在，能区分"后端刻意不下发"与"客户端拿着旧版后端"。
            // 前端顺序取值 `customerName || receiverName`，置 null 后自然回落到订单收件人。
            orderData.put("customerName", null);
            orderData.put("customerPhone", null);
            orderData.put("receiverName", order.getReceiverName());
            orderData.put("receiverPhone", order.getReceiverPhone());
            orderData.put("addressDetail", order.getAddressDetail());
            orderData.put("addressSnapshot", order.getAddressSnapshot());
            orderData.put("quantity", order.getQuantity());
            orderData.put("createTime", order.getCreateTime());

            // 金额与钱去向：快照值原样下发 + 定价来源站名 + 后端文案（前端不做算术、不编文案）
            orderData.putAll(feeInfoOf(order, stationId, stationNames, true));

            // 风险提示（后端唯一来源）：非 null 表示本单涉押金/桶权益。**正常路径下池里不该有这种单**
            // （入池的两个入口都在 OrderWorkflowServiceImpl 里硬拦了），这里下发是为了让"规则上线前
            // 放进池里的历史单"在列表上就能看到提示，而不是等站长点完抢单才被拒。
            orderData.put("crossStationRiskNote", orderWorkflowService.crossStationRiskNote(order));

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
     * 下发给「待本站履约」列表（抢单池 / 他站外派）的金额与钱去向信息。
     *
     * <p>字段与来源（<b>全是快照，无一处重算</b>）：</p>
     * <ul>
     *   <li>{@code deliveryFee} / {@code floorFee} / {@code totalAmount} ← {@code orders.delivery_fee}
     *       / {@code orders.floor_fee} / {@code orders.total_amount}（归属站下单那一刻算出并快照的）；</li>
     *   <li>{@code pricingStationId} / {@code feeStationName} ← {@code orders.station_id}（<b>归属站</b>）
     *       查到的站名：跨站单的费用就是按它的站级计费配置算的，所以它是"定价来源站"；</li>
     *   <li>{@code settleNote} ← 后端按语境生成的文案（抢单池是"认领后"，他站外派是"接单后"）；</li>
     *   <li>{@code settleToMyStation} ← 恒 true：能力/权限上这里的单一旦被本站承接，营收就计入本站。</li>
     * </ul>
     *
     * <p>归属站与本站相同时（同站单，例如指定外派被取消后退回、或本站单被误放进列表）把
     * {@code feeStationName} 置空 —— 同站单说"定价来自本站"是废话，界面也少一行噪音。
     * 这里刻意<b>不下发</b>归属站的任何经营信息（成本、库存、站长联系方式都不带），
     * 池子是跨租户可见面，新增字段必须逐个过一遍"这是不是 A 站的敏感信息"。</p>
     */
    private Map<String, Object> feeInfoOf(Orders order, Long myStationId, Map<Long, String> stationNames,
                                          boolean claimContext) {
        Map<String, Object> info = new HashMap<>();
        info.put("deliveryFee", order.getDeliveryFee() != null ? order.getDeliveryFee() : java.math.BigDecimal.ZERO);
        info.put("floorFee", order.getFloorFee() != null ? order.getFloorFee() : java.math.BigDecimal.ZERO);
        info.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : java.math.BigDecimal.ZERO);

        Long ownerStationId = order.getStationId();
        String ownerName = ownerStationId == null ? null : stationNames.get(ownerStationId);
        if (ownerName == null) ownerName = "归属站";
        boolean crossStation = ownerStationId != null && myStationId != null && !ownerStationId.equals(myStationId);

        info.put("pricingStationId", ownerStationId);
        info.put("feeStationName", crossStation ? ownerName : null);
        info.put("settleToMyStation", true);
        // 整句话由后端拼好，前端原样展示（前端自拼口径文案 = 本仓禁止的做法，AGENTS §6）。
        info.put("settleNote", claimContext
                ? "认领后本单营收（含配送费/楼层费）计入你站；桶、押金与水票仍记在定价来源站 " + ownerName
                : "接单后本单营收（含配送费/楼层费）计入你站；桶、押金与水票仍记在定价来源站 " + ownerName);
        return info;
    }

    /**
     * 站 id → 站名，一次查完。
     *
     * <p>为什么不用 {@code stationMapper.getById(order.getStationId())}：抢单池是 N 单的列表，
     * 那样等于 N 次查询（N+1）。这里把 station 小表整体取一次（本仓的水站数量是个位数），
     * 在内存里按 id 取。</p>
     */
    private Map<Long, String> loadStationNames() {
        Map<Long, String> names = new HashMap<>();
        List<com.example.aquaflow.entity.Station> stations = stationMapper.listAll();
        if (stations == null) return names;
        for (com.example.aquaflow.entity.Station s : stations) {
            if (s != null && s.getId() != null) {
                names.put(s.getId(), s.getName());
            }
        }
        return names;
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
     *
     * <p>[2026-09-18] 与抢单池同一口径：定向外派也是"站长外派"，接收站同样要在动手前
     * 一眼看到这单值多少钱、定价来自哪站、接单后钱归谁。字段与来源见 {@link #feeInfoOf}
     * （{@code feeStationName} / {@code settleNote} / {@code settleToMyStation} 填在 {@link Orders}
     * 的瞬时字段上，金额本来就是 {@code orders} 的列）。</p>
     *
     * <p>⚠️ <b>客户画像一并不下发</b>：这里的每一行都是别站的客户，{@code customerName} /
     * {@code customerPhone} 来自 {@code left join customer}，属于"别站的画像"，
     * 按跨站隔离口径置 null；订单的收件人与地址照常（见 {@link CustomerProfileMask}）。</p>
     */
    @RequireRole("STATION_MANAGER")
    @GetMapping("/orders/directed-incoming")
    public Result<?> getDirectedIncoming() {
        Long stationId = AuthContext.requireStationId();
        List<Orders> incoming = orderMapper.listDirectedIncoming(stationId);
        attachFeeInfo(incoming, stationId, false);
        if (incoming != null) {
            // 整表都是别站客户 → 无条件抹（不像混着本站单的列表那样逐行判跨站）
            for (Orders o : incoming) {
                CustomerProfileMask.mask(o);
            }
        }
        return Result.success(incoming);
    }

    /**
     * 给他站外派列表（{@code Orders} 直出，不像抢单池那样映射成 Map）补上金额与去向信息。
     * 抢单池走 Map（它还要叠商品匹配结果），这里走实体的瞬时字段 —— 两处的
     * {@code feeStationName} / {@code settleNote} / {@code settleToMyStation} 语义必须逐字一致。
     */
    private void attachFeeInfo(List<Orders> orders, Long myStationId, boolean claimContext) {
        if (orders == null || orders.isEmpty()) return;
        Map<Long, String> stationNames = loadStationNames();
        for (Orders o : orders) {
            Map<String, Object> info = feeInfoOf(o, myStationId, stationNames, claimContext);
            o.setFeeStationName((String) info.get("feeStationName"));
            o.setSettleNote((String) info.get("settleNote"));
            o.setSettleToMyStation((Boolean) info.get("settleToMyStation"));
        }
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
