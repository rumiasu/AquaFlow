package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.CompanyInfo;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CompanyInfoMapper;
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
 *       B2B 最怕的是"账面销了、钱没到"，所以核销的入参是订单集合，收款走
 *       {@code markPaidIfCollectable}（全仓唯一收钱入口），两者同一事务。</li>
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
    private final CompanyInfoMapper companyInfoMapper;

    public ReceivableService(ReceivableMapper receivableMapper, OrderMapper orderMapper,
                             CompanyInfoMapper companyInfoMapper) {
        this.receivableMapper = receivableMapper;
        this.orderMapper = orderMapper;
        this.companyInfoMapper = companyInfoMapper;
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

    /** 读客户账期（{@code null} = 未设账期 = 即时结清）。 */
    public Map<String, Object> creditTerms(Long customerId) {
        CompanyInfo info = companyInfoMapper.getByCustomerId(customerId);
        Integer dueDays = info == null ? null : info.getDueDays();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("dueDays", dueDays);
        // 前端不要自己拼这句话（本仓前端禁止自带口径文案）
        data.put("termsText", dueDays == null || dueDays <= 0
                ? "未设账期：下单即按即时结清处理，不产生应付日期"
                : "月结账期 " + dueDays + " 天：挂账订单下单时按此推算应付日期");
        return data;
    }

    /**
     * 设/清客户账期。{@code dueDays} 为 {@code null} 或 0 = 清除账期。
     *
     * <p>只写 {@code company_info.due_days} 一个字段，<b>不碰</b>客户自己填的企业资料
     * （见 {@code CompanyInfoMapper.updateDueDays} 的注释）。</p>
     */
    @Transactional
    public Map<String, Object> setCreditTerms(Long customerId, Integer dueDays) {
        if (dueDays != null && (dueDays < 0 || dueDays > MAX_DUE_DAYS)) {
            throw new BusinessException("账期天数应在 0 ~ " + MAX_DUE_DAYS + " 天之间");
        }
        Integer normalized = (dueDays == null || dueDays <= 0) ? null : dueDays;
        if (companyInfoMapper.updateDueDays(customerId, normalized) == 0) {
            companyInfoMapper.insertCreditTerms(customerId, normalized);
        }
        log.info("[账期] customerId={}, dueDays={}", customerId, normalized);
        return creditTerms(customerId);
    }

    /**
     * 下单时推算应付日期（挂账快照）。
     *
     * <p>只有<b>现金(货到付款)</b>且客户已设账期时才产生应付日期：水票下单即视同已付、
     * 微信支付是即时到账，两者都不该有账期。返回 {@code null} = 即时结清单。</p>
     *
     * <p>由 {@code OrderServiceImpl.createOrder} 在建单时调一次，之后 {@code due_date} 只读。</p>
     */
    public LocalDate resolveDueDate(Long customerId, Integer paymentMethod) {
        if (paymentMethod == null || paymentMethod != com.example.aquaflow.constant.PayMethod.CASH) {
            return null;
        }
        CompanyInfo info = companyInfoMapper.getByCustomerId(customerId);
        if (info == null || info.getDueDays() == null || info.getDueDays() <= 0) {
            return null;
        }
        return LocalDate.now().plusDays(info.getDueDays());
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
