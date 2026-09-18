package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.dto.PaymentCreateDTO;
import com.example.aquaflow.dto.PaymentQuoteDTO;
import com.example.aquaflow.dto.PaymentQuoteItemDTO;
import com.example.aquaflow.dto.PaymentRefundDTO;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.StationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

/**
 * 支付接口 —— <b>顾客端与站长端混装在同一个 {@code /api/payments} 前缀下</b>，靠逐个方法的注解区分。
 *
 * <p><b>顾客侧</b>（无注解 + {@code requireCustomerId()}）：{@code /quote} 试算、
 * {@code POST /api/payments} 发起支付、{@code /by-customer} 查自己的流水。
 * <b>站长侧</b>（{@code STATION_MANAGER}）：确认收款、现金确认、退款、配置、各类列表。</p>
 *
 * <p>资金口径：支付状态的唯一真值是 {@code orders.payment_status}，本类一律只调 service，不自己写资金表。</p>
 *
 * <p><b>两条退款路径（2026-09-18 口径修正，本条此前写作"退款的唯一入口是 refundOrder"，已不准确）：</b></p>
 * <ul>
 *   <li><b>取消订单</b>（客户取消 / 配送员拒单 / 站长解决 / 取消申请审批）→
 *       {@code PaymentService.refundOrder}：退水票 → 退流水 → 退押金 → 清配送中桶 → 回补库存 → 置已取消，
 *       带"已完成/已取消不得再取消"的状态门槛。</li>
 *   <li><b>只退这一笔钱</b>（客户投诉多收/重复付款，订单继续履约）→ {@code PaymentService.refundPayment}，
 *       即本类 {@code PUT /{id}/refund}。<b>它不取消订单</b>，因此两者不是"两套取消逻辑"，
 *       而是"取消一笔订单"与"退一笔流水"两个不同动作。</li>
 * </ul>
 * <p><b>站别口径只有一条：认「结算站」</b>（{@code orders.settle_station_id}，本单营收归谁）。
 * 确认收款（{@code /confirm}、{@code /cash-confirm}）与退款（{@code /{id}/refund}）都走它 ——
 * 现金是结算站的配送员当场收的，钱也记它的账，所以收与退必须是同一站。
 * 未外派的单结算站 = 归属站；被抢单/定向外派后 = 履约站。正本见
 * {@code sql/migration_v47_order_settle_station.sql} 与 {@link #requireRefundStation} 的 javadoc。
 * <!-- [2026-09-18 二次修订] 本段此前写作"确认认履约站、退款认归属站，两条口径不要统一"，
 *      那是「钱与票记归属站」旧口径的产物；产品改为"水费+配送费归实际履约站"后，
 *      **两条口径合并成结算站这一条**。别再按旧注释拆回去。 --></p>
 *
 * <p><b>仍然禁止在本类另写一套回滚/记账逻辑</b>：任何"退水票 / 退押金 / 改资金状态"都必须经 service，
 * 历史上 Controller 各拼半套回滚，抄漏步骤导致过"订单已取消但钱票没退"。本类两个退款端点只是转发。</p>
 */
@RestController
@RequestMapping("/api/payments")
@Slf4j
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private CustomerMapper customerMapper;

    /** 校验订单属于本站履约，否则返回错误 */
    private Result<Void> requireOrderStation(Long orderId) {
        if (orderId == null) return Result.error("缺少订单ID");
        Orders o = orderMapper.getById(orderId);
        if (o == null) return Result.error("订单不存在");
        if (!AuthContext.requireStationId().equals(StationUtil.deliveryStation(o))) {
            return Result.error("无权操作他站订单");
        }
        return null;
    }

    /** 校验支付单归属本站，否则返回错误。
     * <p>站内购买（如线上买水票）产生的支付记录没有关联订单（order_id 为空）。
     * 旧实现对此直接返回「支付记录不存在」，导致水票购买后任何人都无法确认入账 ——
     * 客户付了钱、票永远不到账，且没有任何补救入口。这里改为按水站归属校验。</p>
     */
    private Result<Void> requirePaymentOrderStation(Long paymentId) {
        PaymentRecord p = paymentRecordMapper.getById(paymentId);
        if (p == null) return Result.error("支付记录不存在");
        if (p.getOrderId() == null) {
            Long myStationId = AuthContext.getStationId();
            if (myStationId == null || p.getStationId() == null || !myStationId.equals(p.getStationId())) {
                return Result.error("无权操作他站支付记录");
            }
            return null;
        }
        return requireOrderStation(p.getOrderId());
    }

    /**
     * 校验「这笔钱是本站的」——<b>只用于退款</b>。
     *
     * <p>[2026-09-18 二次修订] 产品裁定：**水费 + 配送费 + 楼层费（本单营收）归实际履约站**，
     * 并为此新增了显式列 {@code orders.settle_station_id}（结算站，正本
     * {@code sql/migration_v47_order_settle_station.sql}）。所以判权改认**结算站**：
     * 未外派的单结算站 = 归属站（归属站可退）；被抢单 / 定向外派后结算站 = 履约站（履约站可退，
     * 因为钱是它收的、也记它的账）。退款冲正流水沿用原流水站别，与收款同源。</p>
     *
     * <p>⚠️ <b>本方法此前叫 {@code requirePaymentOwnerStation}（认归属站）</b>，那是上一版口径
     * （「钱与票记归属站」）的产物；新口径下那个理由不再成立：钱既然归履约站，
     * 让归属站退钱就成了"B 收的钱、A 退的钱"。**判据变了，名字也跟着改，别再改回去。**</p>
     *
     * <p><b>不用 {@code payment_record.station_id} 判权</b>：那一列在写入侧的语义没被钉住
     * （下单即发起时 = 归属站；现金由 {@code recordCashCollection} 写 = 结算站；
     * 定向外派后才发起支付的老数据可能是履约站）。订单才是权威，无订单（线上购票）没有订单可依，
     * 才退回看该列。</p>
     */
    private Result<Void> requireRefundStation(Long paymentId) {
        PaymentRecord p = paymentRecordMapper.getById(paymentId);
        if (p == null) return Result.error("支付记录不存在");
        Long settleStationId;
        if (p.getOrderId() != null) {
            Orders order = orderMapper.getById(p.getOrderId());
            if (order == null) return Result.error("订单不存在");
            settleStationId = StationUtil.settleStation(order);
        } else {
            settleStationId = p.getStationId();
        }
        Long myStationId = AuthContext.getStationId();
        if (myStationId == null || settleStationId == null || !myStationId.equals(settleStationId)) {
            return Result.error("该笔款项由其他水站结算，退款需由结算水站操作");
        }
        return null;
    }

    /** 服务端支付试算（下单前展示，金额以服务端为准） */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestBody @Valid PaymentQuoteDTO dto) {
        Long customerId = AuthContext.requireCustomerId();
        List<Map<String, Object>> itemMaps = dto.getItems().stream().map(i -> {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", i.getProductId());
            m.put("quantity", i.getQuantity());
            return m;
        }).collect(Collectors.toList());
        return Result.success(paymentService.quote(customerId, dto.getStationId(), dto.getPaymentMethod(), itemMaps,
                dto.getAddressId()));
    }

    /** 创建支付记录（金额/新增桶数一律以服务端重算为准） */
    @PostMapping
    public Result<PaymentRecord> create(@RequestBody @Valid PaymentCreateDTO dto) {
        Long orderId = dto.getOrderId();

        // 客户调用时：customerId 强制取自登录态，且订单必须属于本人，防止越权修改他人余额/水票
        Long customerId;
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            return Result.error("订单不存在");
        }
        if ("customer".equals(AuthContext.getUserType())) {
            customerId = AuthContext.requireCustomerId();
            if (!customerId.equals(order.getCustomerId())) {
                return Result.error("无权操作他人订单");
            }
        } else {
            // 员工：仅站长可代录支付，且订单必须属于本人水站
            String role = AuthContext.getRole();
            if (!"STATION_MANAGER".equals(role) && !"manager".equals(role)) {
                return Result.error("权限不足");
            }
            if (!AuthContext.requireStationId().equals(StationUtil.deliveryStation(order))) {
                return Result.error("无权操作他站订单");
            }
            customerId = order.getCustomerId();
        }

        BigDecimal amount = dto.getAmount() != null ? dto.getAmount() : BigDecimal.ZERO;
        BigDecimal waterAmount = dto.getWaterAmount() != null ? dto.getWaterAmount() : BigDecimal.ZERO;
        BigDecimal barrelDeposit = dto.getBarrelDeposit() != null ? dto.getBarrelDeposit() : BigDecimal.ZERO;
        Integer excessBarrels = dto.getExcessBarrels() != null ? dto.getExcessBarrels() : 0;
        Integer paymentMethod = dto.getPaymentMethod();
        Long ticketProductId = dto.getTicketProductId();
        Integer ticketQty = dto.getTicketQty();
        String note = dto.getNote();
        return Result.success(paymentService.createPayment(orderId, customerId, amount, waterAmount, barrelDeposit, excessBarrels, paymentMethod, ticketProductId, ticketQty, note));
    }

    /** 确认支付 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/confirm")
    public Result confirm(@PathVariable Long id) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.confirmPayment(id);
        return Result.success();
    }

    /** 现金收款确认 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/cash-confirm")
    public Result confirmCashById(@PathVariable Long id) {
        Result<Void> check = requirePaymentOrderStation(id);
        if (check != null) return check;
        paymentService.confirmPayment(id);
        return Result.success();
    }

    /**
     * 查询订单支付记录（站长）。
     *
     * <p>调用方：员工端 {@code miniapp-delivery/pages/order/detail} 的「支付流水」区块
     * （只读展示 方式 / 状态 / 金额 / 时间，全部用后端下发的 {@code methodText}/{@code statusText}）。</p>
     *
     * <p>返回的实体含派生文案 {@code methodText} / {@code statusText}（真相源是
     * {@code PayMethod} / {@code PaymentStatus}），<b>前端不得自建 1/2/3 映射表</b>。
     * 站别由 {@link #requireOrderStation} 校验：按<b>履约站</b>判定（与退款入口 {@code requirePaymentOrderStation}
     * 走同一条 {@code requireOrderStation}），跨站取他站订单流水会被拒。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/by-order")
    public Result<List<PaymentRecord>> listByOrderId(@RequestParam Long orderId) {
        Result<Void> check = requireOrderStation(orderId);
        if (check != null) return Result.error(check.getMessage());
        return Result.success(paymentService.listByOrderId(orderId));
    }

    /** 查询客户支付记录（客户自己） */
    @GetMapping("/by-customer")
    public Result<List<PaymentRecord>> listByCustomerId() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(paymentService.listByCustomerId(customerId));
    }

    /** 查询指定客户支付记录（员工管理端） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customer/{customerId}")
    public Result<List<PaymentRecord>> listByCustomerIdForStaff(@PathVariable Long customerId, @RequestParam Long stationId) {
        // [AQ-023] 强制使用登录站长所属水站，忽略客户端传入的 stationId，杜绝跨站查询他站客户支付流水
        Long myStationId = AuthContext.requireStationId();
        return Result.success(paymentRecordMapper.listByCustomerAndStation(customerId, myStationId));
    }

    /** 查询所有支付记录（管理端，支持过滤） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<PaymentRecord>> listAll(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer method,
            @RequestParam(defaultValue = "200") int limit) {
        return Result.success(paymentRecordMapper.listByStation(AuthContext.requireStationId(), status, method, limit));
    }

    /** 查询所有支付记录（管理端，备用） */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all")
    public Result<List<PaymentRecord>> listAllBackup(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(paymentRecordMapper.listAllByStation(AuthContext.requireStationId(), limit));
    }

    /**
     * 待确认收款列表（站长端）。
     *
     * <p>同时覆盖两类此前<b>完全没有界面入口</b>的待确认收款：</p>
     * <ol>
     *   <li>订单现金/微信下单后生成的待收款流水；</li>
     *   <li>「线上买水票」产生的无订单 PENDING 流水 —— 微信支付渠道未接入，
     *       钱只能靠站长核对到账后手工确认，没有入口时顾客付了钱、水票永远不入账。</li>
     * </ol>
     * <p>确认动作复用 {@code PUT /api/payments/{id}/confirm}，其站别校验已支持无订单支付。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/pending")
    public Result<List<Map<String, Object>>> listPending(@RequestParam(defaultValue = "200") int limit) {
        int n = limit <= 0 ? 200 : Math.min(limit, 500);
        List<Map<String, Object>> rows = paymentRecordMapper.listPendingByStation(AuthContext.requireStationId(), n);
        // 本端点返回的是原始列 Map（非实体），派生 getter 不参与序列化，故在此显式补两条文案。
        // 文案真相源仍是常量类（PayMethod / PaymentStatus），**不在 SQL 里复制映射**，
        // 前端也不得自建映射表 —— 历史上两端各写一套导致展示与实际状态不符。
        for (Map<String, Object> r : rows) {
            r.put("methodText", com.example.aquaflow.constant.PayMethod.textOf(asInt(r.get("paymentMethod"))));
            r.put("statusText", com.example.aquaflow.constant.PaymentStatus.textOf(asInt(r.get("status"))));
        }
        return Result.success(rows);
    }

    /** Map 结果里的数值列可能来自不同数值类型，统一取 Integer */
    private static Integer asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    /**
     * 单笔支付流水退款（站长）。
     *
     * <p>调用方：员工端 {@code miniapp-delivery/pages/order/detail} 的「支付流水」区块，
     * 每笔「已付款」的流水一个「退款」按钮（二次确认后调本端点）。<b>顾客端不可调</b>（{@code @RequireRole}）。</p>
     *
     * <p>站别经 {@link #requireRefundStation} 校验 —— <b>认结算站</b>（这笔钱归谁，就由谁退）。</p>
     *
     * <p>业务拒绝一律是 {@code code=1} + 可读文案 —— 前端必须把 {@code message} 原样显示出来：
     * 「微信支付渠道未接入，无法自动原路退回，请线下退款并登记」这类文案是站长唯一的操作指引。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}/refund")
    public Result refund(@PathVariable Long id, @RequestBody @Valid PaymentRefundDTO dto) {
        Result<Void> check = requireRefundStation(id);
        if (check != null) return check;
        paymentService.refundPayment(id, dto.getNote());
        return Result.success();
    }

}
