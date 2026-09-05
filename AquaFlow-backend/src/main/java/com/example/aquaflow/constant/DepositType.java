package com.example.aquaflow.constant;

/**
 * 押金流水类型常量
 */
public class DepositType {

    /** 1 新增押金桶（购桶入账） */
    public static final Integer PURCHASE = 1;

    /** 2 退押金（退桶退押金） */
    public static final Integer RETURN = 2;

    /** 3 丢桶赔偿 */
    public static final Integer COMPENSATION_LOST = 3;

    /** 4 其他调整 */
    public static final Integer ADJUSTMENT = 4;

    /** 5 预收押金（下单预收，待配送确认） */
    public static final Integer PREPAID = 5;

    /** 6 退桶退押金（站长确认退桶） */
    public static final Integer RETURN_BARREL = 6;

    /** 7 异常补偿退押金 */
    public static final Integer EXCEPTION_COMPENSATION = 7;

    /** 8 取消订单释放预收押金 */
    public static final Integer CANCEL_PREPAID = 8;

    private DepositType() {}
}