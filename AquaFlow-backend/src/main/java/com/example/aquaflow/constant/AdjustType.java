package com.example.aquaflow.constant;

/**
 * 站长资产调整类型常量（station_adjustment.adjust_type）。
 *
 * <p>约定：{@code qty} / {@code amount} 一律以<b>正数</b>入参，<b>方向完全由本类型决定</b>。
 * 这样调用方不必记忆"这个类型该传正还是负"，也不会出现同一类型两种相反方向
 * （历史问题：DepositType 7 在两条代码路径里一个增加、一个扣减）。</p>
 *
 * <p>中文文案是本系统唯一来源，前端只渲染后端下发的文案，不自行维护映射表。</p>
 */
public class AdjustType {

    /** 补桶权益（历史补录）：建权益批次，客户由此获得可占用的桶 */
    public static final String BARREL_GRANT = "BARREL_GRANT";

    /** 撤桶权益：按 FIFO 核销批次，退款金额由批次单价决定（不接受调用方指定金额） */
    public static final String BARREL_REVOKE = "BARREL_REVOKE";

    /** 订正欠桶：调整 customer_barrel_over，可为正（补记欠桶）或负（核销欠桶） */
    public static final String OVER_ADJUST = "OVER_ADJUST";

    /** 补录押金（余额增加） */
    public static final String DEPOSIT_GRANT = "DEPOSIT_GRANT";

    /** 扣减押金（余额减少，余额不足则整体拒绝） */
    public static final String DEPOSIT_DEDUCT = "DEPOSIT_DEDUCT";

    /** 补录水票（余额增加） */
    public static final String TICKET_GRANT = "TICKET_GRANT";

    /** 扣减水票（余额减少，余额不足则整体拒绝） */
    public static final String TICKET_DEDUCT = "TICKET_DEDUCT";

    /** 是否为桶类调整（影响桶账，必须写 barrel_record 与批次） */
    public static boolean isBarrel(String type) {
        return BARREL_GRANT.equals(type) || BARREL_REVOKE.equals(type) || OVER_ADJUST.equals(type);
    }

    /** 是否为押金类调整（影响押金账户，必须写 deposit_record） */
    public static boolean isDeposit(String type) {
        return DEPOSIT_GRANT.equals(type) || DEPOSIT_DEDUCT.equals(type);
    }

    /** 是否为水票类调整（影响水票账户，必须写 ticket_record） */
    public static boolean isTicket(String type) {
        return TICKET_GRANT.equals(type) || TICKET_DEDUCT.equals(type);
    }

    /** 是否为"增加"方向（补录类） */
    public static boolean isIncrease(String type) {
        return BARREL_GRANT.equals(type) || DEPOSIT_GRANT.equals(type) || TICKET_GRANT.equals(type);
    }

    /** 是否为"减少"方向（撤销/扣减类） */
    public static boolean isDecrease(String type) {
        return BARREL_REVOKE.equals(type) || DEPOSIT_DEDUCT.equals(type) || TICKET_DEDUCT.equals(type);
    }

    /** 是否需要商品维度（桶与水票必须指定商品；押金按站计提，可不指定） */
    public static boolean requiresProduct(String type) {
        return isBarrel(type) || isTicket(type);
    }

    /** 是否为合法类型 */
    public static boolean isValid(String type) {
        return isBarrel(type) || isDeposit(type) || isTicket(type);
    }

    public static String textOf(String type) {
        if (type == null) return "资产调整";
        switch (type) {
            case BARREL_GRANT:   return "补录桶权益";
            case BARREL_REVOKE:  return "撤销桶权益";
            case OVER_ADJUST:    return "订正欠桶";
            case DEPOSIT_GRANT:  return "补录押金";
            case DEPOSIT_DEDUCT: return "扣减押金";
            case TICKET_GRANT:   return "补录水票";
            case TICKET_DEDUCT:  return "扣减水票";
            default:             return "资产调整";
        }
    }

    private AdjustType() {}
}
