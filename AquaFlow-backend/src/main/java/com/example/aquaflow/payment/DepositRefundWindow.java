package com.example.aquaflow.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

/** C08只判断可信原时间与原款余额；不提交退款或将受理/未知解释成已退款。 */
public final class DepositRefundWindow {
    private DepositRefundWindow() {}
    public enum Route { ORIGINAL_CHANNEL, OFFLINE_HANDOVER, PENDING }
    public record Facts(Instant successTime,BigDecimal originalAmount,BigDecimal refundedAmount,
                        boolean identityVerified,boolean actualAmountVerified,boolean unresolvedRefund) {}
    public record Decision(Route route,BigDecimal remaining,Instant deadline,String reason) {}
    public static Decision decide(Facts facts,Instant now) {
        if(facts==null || now==null)return pending(null,null,"原款事实待核实");
        BigDecimal original,refunded;
        try {original=facts.originalAmount().setScale(2,RoundingMode.UNNECESSARY);refunded=facts.refundedAmount().setScale(2,RoundingMode.UNNECESSARY);}
        catch(RuntimeException invalid){return pending(null,null,"原款金额待核实");}
        if(original.signum()<=0 || refunded.signum()<0 || refunded.compareTo(original)>0)return pending(null,null,"累计实退与原款金额待核实");
        BigDecimal remaining=original.subtract(refunded);
        if(facts.unresolvedRefund())return pending(remaining,null,"原渠道结果仍待核实，不能再次线下退款");
        if(remaining.signum()==0)return pending(remaining,null,"原款已无可退余额，请核对原实退记录");
        if(!facts.identityVerified() || !facts.actualAmountVerified() || facts.successTime()==null || facts.successTime().isAfter(now))return pending(remaining,null,"原支付成功时间、归属或实付金额待核实，不能改交现金");
        Instant deadline;
        try {deadline=facts.successTime().plus(Duration.ofDays(365));}
        catch(DateTimeException | ArithmeticException invalid){return pending(remaining,null,"原支付成功时间待核实");}
        // 用户批准“超过一年”才线下，恰好边界仍优先核原渠道，不假定渠道必定受理。
        return now.isAfter(deadline)?new Decision(Route.OFFLINE_HANDOVER,remaining,deadline,"已核原支付超过365天，退桶交接后按原应退余额登记实际线下退款")
                :new Decision(Route.ORIGINAL_CHANNEL,remaining,deadline,"365天内优先原渠道；受理、处理中、未知或失败均不表示已退款");
    }
    private static Decision pending(BigDecimal amount,Instant deadline,String reason){return new Decision(Route.PENDING,amount,deadline,reason);}
}
