package com.example.aquaflow.payment;

import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.exception.BusinessException;
import org.springframework.stereotype.Component;

/** 内部测试适配器；没有回调 HTTP 入口、不生成或伪造微信签名，不另写业务账。 */
@Component
public final class MockWechatPaymentAdapter {
    private final MockPaymentPolicy policy;
    public MockWechatPaymentAdapter(MockPaymentPolicy policy) { this.policy = policy; }

    /** 调用方必须在同一事务内按订单→流水加锁；仅内部测试可选择失败/取消/延迟，不接受客户端结果。 */
    public void complete(PaymentRecord payment, Outcome outcome, Runnable confirm) {
        policy.requireSelf(payment.getCustomerId());
        if (!payment.isSimulated() || payment.getAmount() == null || payment.getAmount().signum() <= 0)
            throw new BusinessException("模拟付款凭据或金额无效");
        try { payment.getAmount().movePointRight(2).longValueExact(); }
        catch (ArithmeticException invalid) { throw new BusinessException("模拟付款金额必须是有效整分金额"); }
        if (Integer.valueOf(PaymentStatus.PAID).equals(payment.getStatus())) return;
        if (!Integer.valueOf(PaymentStatus.PENDING).equals(payment.getStatus()))
            throw new BusinessException("支付已关闭或退款，不能再次确认");
        if (outcome == Outcome.SUCCESS) confirm.run();
    }

    public enum Outcome { SUCCESS, CANCEL, FAIL, DELAY }
}
