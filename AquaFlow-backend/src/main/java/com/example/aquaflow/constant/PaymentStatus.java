package com.example.aquaflow.constant;

/**
 * 支付状态常量
 */
public class PaymentStatus {
    /** 未支付（订单级别） */
    public static final int UNPAID = 0;
    /** 待支付/待确认 */
    public static final int PENDING = 1;
    /** 已支付 */
    public static final int PAID = 2;
    /** 已退款 */
    public static final int REFUNDED = 3;
    /** 已取消 */
    public static final int CANCELLED = 4;

    /**
     * 支付状态中文文案（全系统唯一来源）。
     * <p>前端禁止自行维护 status → 文案的映射表：历史上两端各写一套，
     * 后端调整口径后前端不跟随，导致展示与实际状态不符。文案一律由后端下发。</p>
     */
    public static String textOf(Integer status) {
        if (status == null) return "未知";
        switch (status) {
            case UNPAID:    return "未支付";
            case PENDING:   return "待收款";
            case PAID:      return "已付款";
            case REFUNDED:  return "已退款";
            case CANCELLED: return "已取消";
            default:        return "未知";
        }
    }

    private PaymentStatus() {}
}
