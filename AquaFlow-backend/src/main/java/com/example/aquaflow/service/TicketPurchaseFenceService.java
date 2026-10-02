package com.example.aquaflow.service;

import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.TicketPurchaseFence;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.mapper.TicketPurchaseFenceMapper;
import com.example.aquaflow.util.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 技术编号终结，不提供已登记款项的客户取消权；付款仍走原编排。 */
@Service
public class TicketPurchaseFenceService {
    private final TicketPurchaseFenceMapper fences;
    private final PaymentRecordMapper payments;
    private final BusinessTime time;

    public TicketPurchaseFenceService(TicketPurchaseFenceMapper fences, PaymentRecordMapper payments, BusinessTime time) {
        this.fences = fences; this.payments = payments; this.time = time;
    }

    private TicketPurchaseFence lock(Long customerId, String key) {
        if (customerId == null || key == null || key.isBlank() || key.length() > 64) {
            throw new BusinessException("购买编号不正确");
        }
        fences.ensure(customerId, key);
        TicketPurchaseFence fence = fences.lock(customerId, key);
        if (fence == null) throw new BusinessException("原购买暂不能核实，请稍后重试");
        return fence;
    }

    /** 必须加入建款事务，持锁直至 payment_record 提交或整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOpen(Long customerId, String key) {
        // 2026-10-02 F-75：仅在页面清键会让迟到 POST 复活；永久封锁必须在资金写入之前查。
        if (lock(customerId, key).getClosedTime() != null) {
            throw new BusinessException("原购买已结束，请使用新的购买编号");
        }
    }

    /** 客户取自 AuthContext 的调用方；返回任何已有流水，绝不撤销待款或承诺已退款。 */
    @Transactional(rollbackFor = Exception.class)
    public CloseResult closeUnregistered(Long customerId, String rawKey) {
        String key = rawKey == null ? null : rawKey.trim();
        TicketPurchaseFence fence = lock(customerId, key);
        // 当前读不可换成普通 SELECT：RR 快照可能看不到等锁期间刚提交的原款。
        PaymentRecord original = payments.getByCustomerAndIdempotencyKeyForUpdate(customerId, key);
        if (original != null) {
            if (original.getOrderId() != null || original.getTicketQty() == null) {
                throw new BusinessException("该编号已有其他款项，请联系水站核实");
            }
            return new CloseResult(key, false, original);
        }
        if (fence.getClosedTime() == null && fences.closeIfOpen(customerId, key, time.now()) != 1) {
            throw new BusinessException("原购买暂不能结束，请查询后重试");
        }
        return new CloseResult(key, true, null);
    }

    public record CloseResult(String idempotencyKey, boolean closed, PaymentRecord payment) {}
}
