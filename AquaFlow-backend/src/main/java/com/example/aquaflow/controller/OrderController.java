package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.OrderCreateDTO;
import com.example.aquaflow.dto.OrderCreateResult;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单接口 —— <b>顾客侧的订单入口</b>（员工侧的写在 {@code DeliveryController} 与
 * {@code OrderWorkflowService} 里）。
 *
 * <p>除 {@code POST /api/orders}（站长/配送员代客建单，限 {@code STATION_MANAGER}/{@code DELIVERY}）外，
 * 本类端点均无 {@code @RequireRole}，靠 {@code AuthContext.requireCustomerId()} 兜身份 ——
 * 这是「顾客自助端点」的标准写法，<b>不是漏标注解</b>
 * （见 {@code aspect/RequireRoleAspect.java} 的「新增端点强制约定」）。</p>
 *
 * <p>{@code PUT /{id}/customer-cancel} 按状态分流：<b>待配送(1)</b> 当场取消；
 * <b>配送中(2)</b> 只提交取消申请，由站长在「审批」页决策（**取消这个动作只有配送端能做**）；
 * <b>已送达(3) 及以上直接拒</b>，文案指向「配送异常」。</p>
 *
 * <p><b>⚠️ 本类曾有 {@code PUT /{id}/status} 旁路端点</b>（只改 status 字段，不执行退款 / 退票 /
 * 退押金 / 回补库存），已于 2026-09-14 作为 P0-4 删除。任何"改状态"都必须走
 * {@code OrderWorkflowService} 的具名方法；守护用例见
 * {@code OrderStateMachineIntegrationTest#statusBypassEndpoint_isGone}。**不要把它加回来。**</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Autowired
    private StationMapper stationMapper;

    @PostMapping("/create")
    public Result<OrderCreateResult> createOrder(@RequestBody @Valid OrderCreateDTO dto) {
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long customerId = AuthContext.requireCustomerId();
            dto.setCustomerId(customerId);
        }
        OrderCreateResult result = orderService.createOrder(dto);
        return Result.success(result);
    }

    @PostMapping
    @RequireRole({"STATION_MANAGER", "DELIVERY"})
    public Result save(@RequestBody Orders orders) {
        orderService.save(orders);
        return Result.success(orders.getId());
    }

    @GetMapping
    public Result<List<Orders>> list(@RequestParam(required = false) Long stationId,
                                     @RequestParam(required = false) Long customerId,
                                     @RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) String createTimeStart,
                                     @RequestParam(required = false) String createTimeEnd,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize) {
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long cid = AuthContext.requireCustomerId();
            if (customerId == null || !customerId.equals(cid)) {
                customerId = cid;
            }
        } else if (AuthContext.isManager() || AuthContext.isDelivery()) {
            // #42: 站长/配送员只能看自己水站的订单，不允许传任意stationId
            Long myStationId = AuthContext.requireStationId();
            if (stationId == null || !stationId.equals(myStationId)) {
                stationId = myStationId;
            }
        }
        // [AQ-046] 分页兜底：默认每页 200 条、上限 500，避免单站上万单一次全量返回拖垮接口
        int size = (pageSize == null || pageSize <= 0) ? 200 : Math.min(pageSize, 500);
        int p = (page == null || page <= 0) ? 1 : page;
        int offset = (p - 1) * size;
        return Result.success(orderService.list(stationId, customerId, status, createTimeStart, createTimeEnd, size, offset));
    }

    @GetMapping("/{id}")
    public Result<Orders> getById(@PathVariable Long id) {
        Orders order = orderService.getById(id);
        if (order == null) {
            return Result.error("订单不存在");
        }
        String userType = AuthContext.getUserType();
        if ("customer".equals(userType)) {
            Long cid = AuthContext.requireCustomerId();
            if (order.getCustomerId() == null || !order.getCustomerId().equals(cid)) {
                return Result.error("无权查看他人订单");
            }
        } else if ("staff".equals(userType)) {
            // 员工分支此前直接返回订单：遍历 id 即可拉取全平台订单（含姓名、电话、地址快照、金额）。
            // 这里补上与 list 接口同款的归属校验。
            Long myStationId = AuthContext.requireStationId();
            if (!myStationId.equals(StationUtil.deliveryStation(order))) {
                return Result.error("无权查看他站订单");
            }
        }
        return Result.success(order);
    }

    /*
     * 已删除：PUT /api/orders/{id}/status（2026-09-14，P0-4）。
     *
     * 该端点只做「isValidTransition 校验 + CAS 改 status 字段」，不执行任何与流转绑定的副作用：
     *   1→5 取消：不退水票、不退押金、不回补库存；
     *   2/3→4 完成：跳过收款确认、押金入账、桶权益核销。
     * 它绕开的正是前门（refundOrder 的 isCancellable 门槛、v28 支付防重、completeOrder 入账链）
     * 辛苦建立的资金/资产不变量，等价于给保险柜配了一把万能后门钥匙。
     *
     * 确认删除前：两端小程序 0 调用（命中过的 /status 均为 BIND_STATUS、BARRELS_RECORDS_STATUS），
     * 测试 0 引用，OrderService.transitionStatus 的唯一 HTTP 入口即本端点。
     * 任一状态流转都必须走带完整副作用的具名入口（OrderWorkflowService.*）。
     */

    /**
     * 客户取消自己的订单。
     *
     * <p>按状态分流（2026-09-14 起）：<b>待配送(1)</b> 当场取消并走完整退款链；
     * <b>配送中(2) / 已送达(3)</b> 不能自助取消，只提交<b>取消申请</b>，由站长审批
     * （见 {@code OrderWorkflowService#requestCancelByCustomer}）。</p>
     *
     * <p>这是<b>顾客自助端点</b>：不加 {@code @RequireRole}，身份一律由
     * {@code AuthContext.requireCustomerId()} 强制获取、不信任请求参数
     * （见 {@code RequireRoleAspect} 类注释里的「新增端点强制约定」）。</p>
     */
    @PutMapping("/{id}/customer-cancel")
    public Result customerCancel(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        orderService.cancelByCustomer(id, customerId);
        return Result.success();
    }

    /**
     * 我当前的服务水站（用于登录后自动选站）。
     * <p>优先取最近一笔订单的水站；<b>没有下过单的新客户</b>回退到水站给其配置的绑定关系
     * （customer_station_config）——旧实现只查订单，新客户恒定拿到 null，
     * 首页只能退化成"请选择服务水站"，即便水站早已把该客户纳入管辖。</p>
     */
    @GetMapping("/my-station")
    public Result<?> getMyLatestStation() {
        Long customerId = AuthContext.requireCustomerId();

        Map<String, Object> station = orderMapper.getLatestStationByCustomerId(customerId);
        if (station != null && station.get("stationId") != null) {
            return Result.success(station);
        }

        // 回退：客户与水站的绑定关系（可能来自水站代建/认领）
        List<CustomerStationConfig> configs = customerStationConfigMapper.listByCustomer(customerId);
        if (configs != null && !configs.isEmpty()) {
            CustomerStationConfig config = configs.get(0);
            Station s = config.getStationId() != null ? stationMapper.getById(config.getStationId()) : null;
            if (s != null) {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("stationId", s.getId());
                fallback.put("stationName", s.getName());
                return Result.success(fallback);
            }
        }

        return Result.success(null);
    }
}
