package com.example.aquaflow.service;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.exception.BusinessException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;
import java.util.*;
@Service
public class BusinessWaitingService {
    @Autowired private BarrelBusinessPolicy policy;
    @Autowired private BusinessWaitingMapper mapper;
    @Autowired private ApprovedBarrelReturnService approvedReturns;
    @Autowired private BarrelLedgerService barrelLedger;
    @Autowired private ConsumptionRefundMapper locks;
    @Autowired private PaymentService payments;
    @Autowired @Lazy private BusinessWaitingService proxy;
    @Value("${aquaflow.barrel.unpaid-timeout-minutes:15}") private int minutes;
    @Value("${aquaflow.barrel.maintenance-enabled:true}") private boolean maintenance;
    /** 不猜测渠道处理中款项；仅无活跃支付请求的未付新单自动释放占用。 */
    @Scheduled(fixedDelayString="${aquaflow.barrel.maintenance-interval-ms:60000}")
    public void expireUnpaid() {
        if (!policy.isEnabled() || !maintenance || minutes<=0) return;
        for (Long order:mapper.unpaidExpired(minutes)) {
            try { proxy.expire(order); } catch (BusinessException ignored) { /* 并发已付款/已取消时留给下一轮重新判断 */ }
        }
    }
    @Transactional public void expire(Long id) {
        if (!policy.isEnabled()) return;
        Orders order=locks.lockOrder(id);
        if (order==null || order.getStatus()!=1 || order.getPaymentStatus()!=0 || order.getPaymentMethod()==2
                || order.getCreateTime().isAfter(java.time.LocalDateTime.now().minusMinutes(minutes)) || mapper.hasActivePayment(id)>0) return;
        payments.refundOrder(id,"未付订单超过预留时间，自动取消并释放库存、权益占用；独立已付押金不退");
    }
    /** 只读定位本站原申请；不经历史列表扫描，已办完仍能看原凭据。 */
    @Transactional(readOnly = true)
    public com.example.aquaflow.entity.BarrelRecord returnRecord(Long station, Long recordId) {
        if (station == null) throw new BusinessException("当前账号未绑定水站");
        var record = mapper.returnRecord(recordId, station);
        if (record == null) throw new BusinessException("未找到本站的退桶申请，请核实申请编号");
        record.setReturnDetail(approvedReturns.detail(recordId));
        if (record.getCustomerId() != null && record.getProductId() != null) {
            record.setOwedBuckets(barrelLedger.overQty(record.getCustomerId(), station, record.getProductId()));
        }
        return record;
    }

    /** 首页和办理页共享的总数；不拿最多 200 条的列表长度冒充总数。 */
    @Transactional(readOnly = true)
    public Map<String,Integer> pendingCounts(Long station) {
        if (station == null) throw new BusinessException("当前账号未绑定水站");
        Map<String,Integer> counts = new LinkedHashMap<>();
        counts.put("waitingStock", mapper.countWaitingStock(station));
        for (String key : List.of("returnsTotal", "returnRefund", "recoveriesTotal", "recoverySend", "recoveryReceive", "barrelsTotal", "barrelHandover", "barrelDispute")) {
            counts.put(key, null);
        }
        // 2026-10-02：结构未就绪不是“没有待办”；新业务读数留未知，历史缺货仍可核对。
        if (!policy.hasSchema()) return counts;
        addCounts(counts, mapper.countWaitingReturns(station), "returnsTotal", "returnRefund");
        addCounts(counts, mapper.countWaitingRecoveries(station), "recoverySend", "recoveryReceive");
        addCounts(counts, mapper.countWaitingBarrels(station), "barrelHandover", "barrelDispute");
        counts.put("recoveriesTotal", counts.get("recoverySend") + counts.get("recoveryReceive"));
        counts.put("barrelsTotal", counts.get("barrelHandover") + counts.get("barrelDispute"));
        return counts;
    }

    /** 员工办理页的只读责任清单；站别由员工控制器的登录态提供，顾客端不能调用。 */
    @Transactional(readOnly = true)
    public Map<String,Object> waiting(Long station) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("counts", pendingCounts(station));
        out.put("limit", BusinessWaitingMapper.WAITING_LIMIT);
        out.put("schemaAvailable", policy.hasSchema());
        out.put("stock", describe(mapper.waitingStock(station), "stock"));
        out.put("returns", policy.hasSchema() ? describe(mapper.delayedReturns(station), "returns") : List.of());
        out.put("recoveries", policy.hasSchema() ? describe(mapper.waitingRecoveries(station), "recoveries") : List.of());
        out.put("barrels", policy.hasSchema() ? describe(mapper.waitingBarrels(station), "barrels") : List.of());
        return out;
    }

    private static void addCounts(Map<String,Integer> counts, Map<String,Object> row, String... keys) {
        for (String key : keys) {
            // SQL 失败/缺投影必须出声；不能用 null→0 隐藏未核对的责任。
            Object value = row == null ? null : row.get(key);
            if (!(value instanceof Number n)) throw new BusinessException("待办数量未能核对，请刷新重试");
            counts.put(key, n.intValue());
        }
    }

    private static List<Map<String,Object>> describe(List<Map<String,Object>> rows, String kind) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> source : rows) {
            Map<String,Object> row = new LinkedHashMap<>(source);
            String action = String.valueOf(row.get("nextAction"));
            if ("stock".equals(kind)) action = "stock";
            if ("returns".equals(kind)) action = "RECEIVED".equals(row.get("status")) ? "refund" : "approve";
            String next = switch (action) {
                case "stock" -> "查看原单并补齐履约站库存";
                case "refund" -> "核实原款并实际退还押金";
                case "approve" -> "查看原申请并审批退桶安排";
                case "sent" -> "登记已实际交付返还款";
                case "received" -> "确认返还款已实际到账";
                case "barrels" -> "确认本方实物或桶款已交接";
                case "proposal" -> "提出桶争议处理方案";
                case "agree" -> "核实并同意桶争议处理方案";
                default -> throw new BusinessException("待办责任未能核对，请刷新重试");
            };
            String reason = switch (action) {
                case "stock" -> "库存预留不足，客户仍在等待送水";
                case "refund" -> "已收桶或确认无需交桶，押金尚未实际退款";
                case "approve" -> "申请等待审批超过现有提醒时间";
                case "sent" -> "原站间款已冲销，本站尚未登记实际返还";
                case "received" -> "对方已登记交付，本站尚未确认实际到账";
                case "barrels" -> "本方尚未确认实际交接";
                default -> "桶来源或交接责任有争议，等待本方确认方案";
            };
            row.put("nextAction", action);
            row.put("nextActionText", next);
            row.put("waitingReason", reason);
            // 净桶凭据只有接单时间，不能伪称它是实际送达/争议发生时间。
            row.put("waitingSinceLabel", switch (action) {
                case "stock" -> "需求时间";
                case "refund" -> "交接确认时间";
                case "approve" -> "申请时间";
                case "sent" -> "追收登记时间";
                case "received" -> "返还款交付登记时间";
                default -> "接单时间";
            });
            out.add(row);
        }
        return out;
    }
}
