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

    private PaymentStatus() {}
}
