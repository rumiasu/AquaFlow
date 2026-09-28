package com.example.aquaflow.controller;

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
 * <p><b>本类全部端点都靠 {@code AuthContext} 取身份</b>（顾客自助端点用
 * {@code requireCustomerId()}，员工端点用 {@code requireStationId()}），<b>没有一处</b>信任请求体里的
 * {@code customerId} / {@code stationId} —— 这是本类的既定写法，<b>不是漏标注解</b>
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
 *
 * <p><b>⚠️ 本类曾有 {@code POST /api/orders}（裸 {@code Orders} 实体整行更新）</b>，
 * 已于 2026-09-25 作为架构评审问题 2 删除（登记见 {@code docs/audit/删除登记表.md}）。
 * 它由客户端直传实体，能改金额、改履约站（连带结算站）、改配送员、改支付方式，且
 * {@code where id=#{id}} 没有状态 CAS —— 与并发业务动作互相覆盖时会把状态写回旧值。
 * 订单的任何修改都必须走具名业务命令（{@code OrderWorkflowService}）或专用列更新
 * （{@code OrderMapper} 里那些带 expected-state 的方法）。
 * 守护用例见 {@code ArchReviewFixesIntegrationTest#genericOrderUpdateEndpoint_isGone}
 * （断言：请求被"方法不支持"拒回、订单金额与结算站一个字段都没变 ——
 *   注意它**不是 404**，因为同路径上还有 {@code GET} 订单列表）。
 * <b>不要以"兼容旧调用方"为名把它加回来</b> —— 两端小程序零调用方（2026-09-25 核实）。</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /** 自助支付能力投影用（{@code canSelfPay}）：渠道能力只有支付侧知道（见 PaymentServiceImpl）。 */
    @Autowired
    private com.example.aquaflow.service.PaymentService paymentService;

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

    /*
     * 已删除：POST /api/orders（2026-09-25，架构评审问题 2）。
     *
     * 原实现：@RequireRole({"STATION_MANAGER","DELIVERY"}) + @RequestBody Orders（裸实体），
     * 调 orderService.save 走 OrderMapper.xml 的选择性更新。
     * 为什么删：请求体是持久化实体，客户端可编造任意字段 —— 实测能把 delivery_station_id
     * （连带 settle_station_id）改到别站、把 total_amount 改成 0.01；service 只回填了
     * station_id / status / payment_status / createTime，其余一概照写。且 where id=#{id}
     * 无 CAS，与并发业务动作（如 completeDelivery 的状态 CAS）互相覆盖时会把状态写回旧值。
     * 删除依据：2026-09-25 核实零调用方 —— 两端小程序全仓 0 处调用（只调 /api/orders/create
     * 与 GET /api/orders）；后端测试仅 TamperResistanceIntegrationTest 发过一次 "{}" 用于
     * 断言被拒，本次已改为断言 404。登记见 docs/audit/删除登记表.md。
     * 正确做法：修改订单只能走 OrderWorkflowService 的具名命令，或 OrderMapper 里带
     * expected-state 的专用列更新。不要把它加回来。
     */

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
        } else {
            // [2026-09-25 架构评审问题 1] 原来这里**没有 else**：身份既不是客户、也不是
            // 站长/配送员时（典型是 wx-login-staff 签发的 role=UNSELECTED 引导会话，
            // stationId=null），两个过滤条件保持请求原样 ⇒ 都为 null ⇒ 下面的 SQL 走进
            // "一个 <if> 都不命中"的分支，**返回全部水站的订单**（还带客户姓名/电话/地址快照）。
            // 实测：这种会话任何微信用户都能自助取得（/api/auth/wx-login-staff 在认证白名单里）。
            //
            // 正确做法（两层，缺一不可）：
            //   ① 本方法显式声明"我认哪几类身份"，未知身份**默认拒绝** —— 不要再写成
            //      "参数为空就查全部"这种靠调用方自觉的隐式语义；
            //   ② AuthInterceptor 已把 UNSELECTED 会话限制在引导端点上（第一道闸门），
            //      这里是第二道 —— 将来新增身份类型时它仍然会拦住。
            return Result.error("仅客户或已绑定水站的员工可查询订单");
        }
        // [AQ-046] 分页兜底：默认每页 200 条、上限 500，避免单站上万单一次全量返回拖垮接口
        int size = (pageSize == null || pageSize <= 0) ? 200 : Math.min(pageSize, 500);
        int p = (page == null || page <= 0) ? 1 : page;
        int offset = (p - 1) * size;
        // [2026-09-27 产品裁定] 员工视野要挡掉"还没收到钱"的单（`payment_status = 2 或 payment_method = 2`）：
        // 原先把未付款的微信单也列给站长，他既不该派单也不知道该不该管 —— 产品原话「不应该，减少杂乱度」。
        // ⚠️ **顾客端传 false**：客户自己那张没付钱的单必须看得见（他要在列表里点进去继续付款，
        //    挡掉等于钱收不到）。身份判据用上面那段已经归一化过的分支结果，不重新解析请求参数。
        boolean staffScope = !"customer".equals(userType);
        return Result.success(orderService.list(stationId, customerId, status, createTimeStart, createTimeEnd,
                size, offset, staffScope));
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
        // 能否自助支付由**服务端按当前渠道能力**投影（契约 A3）：实体自己不知道这个部署开了哪些渠道，
        // 一律 false 会让"模拟渠道开着时未付微信单"没有入口，一律 true 又会给出现金单的假支付按钮。
        order.setSelfPayAllowed(paymentService.canSelfPay(order));
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
