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

    /**
     * 押金流水类型中文文案（全系统唯一来源）。
     * 前端一律渲染后端下发的文案，禁止自行维护 type -> 文案映射表。
     */
    public static String textOf(Integer type) {
        if (type == null) return "押金变动";
        switch (type) {
            case 1: return "押金入账";
            case 2: return "退押金";
            case 3: return "丢桶赔偿";
            case 4: return "人工调整";
            case 5: return "预收押金";
            case 6: return "退桶退押金";
            case 7: return "异常补偿";
            case 8: return "取消订单释放";
            default: return "押金变动";
        }
    }

    /** 该类型是否为"客户退回押金"（金额减少），用于生成 +/- 展示 */
    public static boolean isRefund(Integer type) {
        if (type == null) return false;
        return type == 2 || type == 6 || type == 7 || type == 8;
    }

    private DepositType() {}
}