package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.SettlementCycle;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerStationConfigMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ReceivableMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应收账款（2026-09-17，规格见 {@code docs/design/20} §1 的 A 层）。
 *
 * <p><b>它做的事只有一件：给已有的「待收款」加上账期维度。</b>金额的真相源仍是
 * {@code orders.payment_status = 1 AND status != 5}（与 {@code DashboardMapper} 的
 * 待收款合计逐字同源），本类<b>不新造任何金额口径</b>。</p>
 *
 * <p>三条口径（写在这里是因为它们都是"改这里会踩坑"级别的）：</p>
 * <ol>
 *   <li><b>账期必须快照进订单</b>（{@code orders.due_date}）—— 与地址/金额快照同源的理由：
 *       站长事后改客户账期，不能改到历史单的到期日。下单时算一次，之后只读。</li>
 *   <li><b>核销 ⟹ 已收款</b>，由 {@code OrderMapper.settleIfCollected} 的 CAS 钉住。
 *       B2B 最怕的是"账面销了、钱没到"，所以核销的入参是订单集合，收款与核销同一事务。
 *       ⚠️ 收款必须**两步都做**：先 {@code PaymentService.recordCashCollection} 补写 PAID 流水，
 *       再 {@code markPaidIfCollectable} 置 {@code payment_status = 2}。只做后者的话，
 *       对账<b>等式2</b>会判「已付但无凭证」不平 —— 站长每核销一单日结就报一次假警报。
 *       （2026-09-17 实测：本类最初只调了 markPaidIfCollectable，确实踩中这一条。）</li>
 *   <li><b>逾期只提醒、不改任何金额</b> —— 对齐本仓"欠桶只提醒不阻断"的既有风格
 *       （AGENTS §1）。本类没有任何写金额的分支。</li>
 * </ol>
 *
 * <p>⚠️ {@code settlement_status} 只在<b>挂账单</b>（{@code due_date IS NOT NULL}）上有意义；
 * 即时结清的单不参与核销流程。判别式就是 {@code due_date IS NOT NULL}。</p>
 */
@Service
@Slf4j
public class ReceivableService {

    /** 账期上限（天）：防止把 999999 这类手滑值写成"下辈子到期"，也让逾期天数有意义 */
    public static final int MAX_DUE_DAYS = 365;

    private final ReceivableMapper receivableMapper;
    private final OrderMapper orderMapper;
    /** 账期的读与写都在这里（v60 起账期是**站级**配置，见 constant/SettlementCycle 的注释）。 */
    private final CustomerStationConfigMapper customerStationConfigMapper;
    /** 收款一律走支付链路（PAID 只能由它写入，见 recordCashCollection 的注释）。 */
    private final PaymentService paymentService;

    public ReceivableService(ReceivableMapper receivableMapper, OrderMapper orderMapper,
                             CustomerStationConfigMapper customerStationConfigMapper,
                             PaymentService paymentService) {
        this.receivableMapper = receivableMapper;
        this.orderMapper = orderMapper;
        this.customerStationConfigMapper = customerStationConfigMapper;
        this.paymentService = paymentService;
    }

    /**
     * 本站应收账款总览：待收款合计 + 逾期合计 + 按客户的账龄明细。
     *
     * <p>合计由明细累加得到，<b>不另写一条聚合 SQL</b> —— 两条 SQL 迟早会算出两个数
     * （本站"计价双轨"事故的同形风险，见 {@code util/PriceUtil} 文件头）。</p>
     */
    public Map<String, Object> overview(Long stationId) {
        List<Map<String, Object>> rows = receivableMapper.listByCustomer(stationId);

        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        int overdueCustomers = 0;
        for (Map<String, Object> row : rows) {
            BigDecimal amount = toDecimal(row.get("outstandingAmount"));
            BigDecimal od = toDecimal(row.get("overdueAmount"));
            outstanding = outstanding.add(amount);
            overdue = overdue.add(od);
            if (od.signum() > 0) {
                overdueCustomers++;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outstandingAmount", outstanding);
        data.put("overdueAmount", overdue);
        data.put("customerCount", rows.size());
        data.put("overdueCustomerCount", overdueCustomers);
        data.put("customers", rows);
        // 站点级口径说明由后端下发，前端不要自己拼（本仓前端禁止自带口径文案）
        data.put("scopeNote", "待收款 = 支付状态为待收款且未取消的订单合计；逾期 = 其中已过应付日期的部分");
        return data;
    }

    /** 待收款明细（可按客户 / 只看逾期）。 */
    public List<Map<String, Object>> orders(Long stationId, Long customerId, boolean onlyOverdue) {
        return receivableMapper.listOrders(stationId, customerId, onlyOverdue);
    }

    /**
     * 核销：对选中的订单**先收款、再核销**（同一事务）。
     *
     * <p>语义上这是"月结收到一笔钱，把这批单清掉"。已核销的单<b>跳过而不报错</b>（幂等重放：
     * 站长重复点一次不该看到一个红字弹窗）；其余任何一处失败都<b>整批回滚</b>并说明是哪一单、
     * 为什么 —— 对齐 AGENTS §8.17「要求了却没执行也必须失败，不能标成已执行」。</p>
     *
     * @return {settledCount, alreadySettledCount, collectedCount, settledAmount, orderIds}
     */
    @Transactional
    public Map<String, Object> settle(Long stationId, Long customerId, List<Long> orderIds) {
        if (customerId == null) {
            throw new BusinessException("请先选择客户");
        }
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BusinessException("请先选择要核销的订单");
        }

        BigDecimal settledAmount = BigDecimal.ZERO;
        int settledCount = 0;
        int alreadySettled = 0;
        int collectedCount = 0;

        for (Long orderId : orderIds) {
            if (orderId == null) {
                throw new BusinessException("订单 ID 不能为空");
            }
            // 站别由 SQL 强制（where id = ? and station_id = ?）—— 不接受调用方传进来的归属
            Map<String, Object> order = receivableMapper.getForSettle(orderId, stationId);
            if (order == null) {
                throw new BusinessException("订单不存在或不属于本水站：" + orderId);
            }
            if (!customerId.equals(asLong(order.get("customerId")))) {
                throw new BusinessException("订单 " + orderId + " 不属于该客户，不能合并核销");
            }
            if (asInt(order.get("status")) == OrderStatus.CANCELLED) {
                // 取消单的钱已经原路退回，核销它就等于凭空抹掉一笔应收
                throw new BusinessException("订单 " + orderId + " 已取消，不得核销");
            }
            if (asInt(order.get("settlementStatus")) == 2) {
                alreadySettled++;
                continue;
            }
            if (asInt(order.get("paymentStatus")) != PaymentStatus.PAID) {
                // ⚠️ 必须先补写 PAID 流水，**不能只改 orders.payment_status**。
                // 对账等式2 把「payment_status=2 却查不到 PAID 流水」判为不平（已付无凭证），
                // 于是站长每核销一单，日结就报一次不平、还会发 SYSTEM 告警 —— 真问题会被淹没。
                // 本仓的领域原则是「PAID 只能由支付链路写入」，所以这里复用
                // PaymentService.recordCashCollection（它幂等），而不是自己 insert 一条流水。
                paymentService.recordCashCollection(orderId, "站长核销应收账款");
                if (orderMapper.markPaidIfCollectable(orderId) == 0) {
                    throw new BusinessException("订单 " + orderId + " 收款失败：状态已变更，请刷新后重试");
                }
                collectedCount++;
            }
            if (orderMapper.settleIfCollected(orderId) == 0) {
                // 走到这里说明收款成功却没核销上 —— 宁可失败出声，也不要让站长以为账销了
                throw new BusinessException("订单 " + orderId + " 核销失败：未处于已收款状态");
            }
            settledAmount = settledAmount.add(toDecimal(order.get("totalAmount")));
            settledCount++;
        }

        log.info("[应收核销] stationId={}, customerId={}, 核销={} 单/{} 元, 本次新增收款={} 单, 已核销跳过={} 单",
                stationId, customerId, settledCount, settledAmount, collectedCount, alreadySettled);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settledCount", settledCount);
        result.put("alreadySettledCount", alreadySettled);
        result.put("collectedCount", collectedCount);
        result.put("settledAmount", settledAmount);
        result.put("orderIds", orderIds);
        return result;
    }

    /**
     * 读该客户在**本站**的账期（{@code dueDays} 为空 = 未设账期 = 即时结清）。
     *
     * <p>[v60] 从客户级改成站级：同一家公司在 A 站可以月结、在 B 站只能现结。
     * 返回体里带上平台默认值，供前端渲染「一键套用」按钮 ——
     * 前端不要自己拼数字，更不要自己拼 {@code termsText}（本仓前端禁止自带口径文案）。</p>
     */
    public Map<String, Object> creditTerms(Long customerId, Long stationId) {
        CustomerStationConfig cfg = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        Integer dueDays = cfg == null ? null : cfg.getDueDays();
        String cycle = cfg == null ? null : cfg.getSettlementCycle();
        // 「有没有账期」只有一个判据：天数 > 0 且周期是月结。拿不准（周期为空/不是月结）一律按现结。
        boolean credit = dueDays != null && dueDays > 0 && SettlementCycle.MONTHLY.equals(cycle);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("stationId", stationId);
        data.put("dueDays", credit ? dueDays : null);
        data.put("settlementCycle", credit ? cycle : SettlementCycle.IMMEDIATE);
        data.put("settlementCycleText", SettlementCycle.textOf(credit ? cycle : SettlementCycle.IMMEDIATE));
        data.put("platformDefaultDueDays", SettlementCycle.PLATFORM_DEFAULT_DUE_DAYS);
        data.put("platformDefaultCycleText", SettlementCycle.textOf(SettlementCycle.PLATFORM_DEFAULT));
        data.put("termsText", credit
                ? SettlementCycle.textOf(cycle) + "账期 " + dueDays + " 天：从当月最后一天起算，下单时快照进订单"
                : "未设账期：下单即按即时结清处理，不产生应付日期");
        return data;
    }

    /**
     * 设/清该客户在**本站**的账期。{@code dueDays} 为 {@code null} 或 0 = 清除账期（现结）。
     *
     * <p>[v60] 账期从客户级 {@code company_info.due_days} 改为**站级**
     * {@code customer_station_config} —— A 站设的账期不该在 B 站生效，B 站更不该能改掉它。
     * 只写 {@code due_days} 与 {@code settlement_cycle} 两列，
     * <b>不碰</b>客户自己填的企业资料，也<b>不碰</b> {@code offline_payment_enabled}
     * （"能不能赊账"与"账期多久"是两个决定）。</p>
     *
     * <p>⚠️ <b>只影响之后下的单</b>：{@code orders.due_date} 是下单时快照、之后只读
     * （与金额/地址快照同源）。站长改完发现"老单没变"是**设计如此**；
     * 要把未结账单也改过来，用 {@link #recalculateDueDates}（会留痕）。</p>
     */
    @Transactional
    public Map<String, Object> setCreditTerms(Long customerId, Long stationId, Integer dueDays, String cycle) {
        if (dueDays != null && (dueDays < 0 || dueDays > MAX_DUE_DAYS)) {
            throw new BusinessException("账期天数应在 0 ~ " + MAX_DUE_DAYS + " 天之间");
        }
        boolean off = dueDays == null || dueDays <= 0;
        Integer normalizedDays;
        String normalizedCycle;
        if (off) {
            normalizedDays = null;
            normalizedCycle = SettlementCycle.IMMEDIATE;
        } else {
            // 暂只支持月结（有意收窄，见 constant/SettlementCycle 的注释）。
            // 传了别的值**直接拒**，不静默按现结处理 —— 静默降级会让站长以为设上了。
            if (cycle != null && !SettlementCycle.MONTHLY.equals(cycle)) {
                throw new BusinessException("暂只支持「现结」与「月结」两种结算周期");
            }
            normalizedDays = dueDays;
            normalizedCycle = SettlementCycle.MONTHLY;
        }
        // 行可能不存在（该客户在本站还没有任何配置），先确保有行再改
        customerStationConfigMapper.ensureExists(customerId, stationId);
        customerStationConfigMapper.updateCreditTerms(customerId, stationId, normalizedDays, normalizedCycle);
        log.info("[账期] customerId={}, stationId={}, dueDays={}, cycle={}",
                customerId, stationId, normalizedDays, normalizedCycle);
        return creditTerms(customerId, stationId);
    }

    /**
     * 下单时推算应付日期（挂账快照）。
     *
     * <p>只有<b>现金(货到付款)</b>且该客户在**该站**已设账期时才产生应付日期：
     * 水票下单即视同已付、微信支付是即时到账，两者都不该有账期。
     * 返回 {@code null} = 即时结清单 —— 这也是「欠款即停」区分挂账单的判据
     * （{@code PaymentServiceImpl.offlinePaymentBlockReason} 用的是 {@code due_date IS NOT NULL}）。</p>
     *
     * <p><b>算法</b>（v60）：{@code 月结} → 锚点 = <b>当月最后一天</b>，{@code due_date = 锚点 + dueDays}。
     * 例：9/5 下单、月结 30 天 → 9/30 + 30 = 10/30（"次月底前付"，企业主流）。</p>
     *
     * <p>由 {@code OrderServiceImpl.createOrder} 在建单时调一次，之后 {@code due_date} 只读。</p>
     */
    public LocalDate resolveDueDate(Long customerId, Long stationId, Integer paymentMethod) {
        if (paymentMethod == null || paymentMethod != PayMethod.CASH) {
            return null;
        }
        if (customerId == null || stationId == null) {
            return null;
        }
        CustomerStationConfig cfg = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        if (cfg == null || cfg.getDueDays() == null || cfg.getDueDays() <= 0) {
            return null;
        }
        // 只认月结：周期为空的行（历史数据 / 人手塞的）按现结处理 —— 拿不准就不挂账
        if (!SettlementCycle.MONTHLY.equals(cfg.getSettlementCycle())) {
            return null;
        }
        LocalDate today = LocalDate.now();
        return today.withDayOfMonth(today.lengthOfMonth()).plusDays(cfg.getDueDays());
    }

    /** 按"某张单自己下单的那个月"算锚点（重算用，见 {@link #recalculateDueDates}）。 */
    private LocalDate anchorOf(LocalDate orderDate) {
        return orderDate.withDayOfMonth(orderDate.lengthOfMonth());
    }

    /**
     * 把该客户在该站**还没结清**的挂账单，按当前账期重算应付日期 —— 站长显式触发，会留痕。
     *
     * <p><b>为什么需要它</b>：{@code orders.due_date} 是下单时快照、之后只读（有意为之，
     * 与金额/地址快照同源）。于是站长把账期从 30 天改成 15 天之后，**已经下的单还是 30 天**，
     * 他会以为系统坏了。本方法给一个把存量改过来的显式动作。</p>
     *
     * <p><b>锚点用"这张单自己下单的那个月"</b>，不是今天 —— 否则重算会把到期日往后推，
     * 等于给客户**延长**账期，与站长的本意相反。</p>
     *
     * <p><b>只动"还没结清"的挂账单</b>（{@code payment_status = 1 AND status <> 5 AND due_date IS NOT NULL}）：
     * 已付/已取消的单不该被翻旧账；即时结清的单（{@code due_date} 为空）下单时就没有账期，不该凭空长出一个。</p>
     *
     * <p><b>留痕</b>：每张被改动的单往 {@code orders.special_note} 追加一行
     * {@code [账期重算] 旧日期 → 新日期} —— 不新建表，谁什么时候改的一目了然。</p>
     *
     * @return 实际重算了多少张（没有可改的返回 0，不报错）
     */
    @Transactional
    public int recalculateDueDates(Long customerId, Long stationId, Long operatorId) {
        CustomerStationConfig cfg = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        boolean credit = cfg != null && cfg.getDueDays() != null && cfg.getDueDays() > 0
                && SettlementCycle.MONTHLY.equals(cfg.getSettlementCycle());
        if (!credit) {
            // 现结客户没有挂账单可重算 —— 这是配置状态问题，用业务错误说清，别静默返回 0
            throw new BusinessException("该客户在本站当前是「现结」，没有可重算的挂账单");
        }
        List<Orders> open = orderMapper.listUnsettledWithDueDate(customerId, stationId);
        if (open == null || open.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Orders o : open) {
            if (o.getCreateTime() == null || o.getDueDate() == null) {
                continue;
            }
            LocalDate fresh = anchorOf(o.getCreateTime().toLocalDate()).plusDays(cfg.getDueDays());
            if (fresh.equals(o.getDueDate())) {
                continue;   // 已经是这个日期，不动它（避免留一堆无意义的痕迹）
            }
            if (orderMapper.updateDueDateIfUnsettled(o.getId(), fresh) == 0) {
                continue;   // 期间被付掉/取消了 —— CAS 不命中就跳过，不报错
            }
            orderMapper.appendSpecialNote(o.getId(),
                    "[账期重算] " + o.getDueDate() + " → " + fresh + "（操作人 " + operatorId + "）");
            changed++;
        }
        log.info("[账期重算] customerId={}, stationId={}, operatorId={}, 改动 {} 张",
                customerId, stationId, operatorId, changed);
        return changed;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(String.valueOf(v));
    }

    private static int asInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
