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

    /** 4 人工调整（**方向固定为扣减**，文案已明确，避免与 9 混淆） */
    public static final Integer ADJUSTMENT = 4;

    /** 5 预收押金（下单预收，待配送确认） */
    public static final Integer PREPAID = 5;

    /** 6 退桶退押金（站长确认退桶） */
    public static final Integer RETURN_BARREL = 6;

    /** 7 异常补偿退押金（**方向固定为扣减**） */
    public static final Integer EXCEPTION_COMPENSATION = 7;

    /** 8 取消订单释放预收押金 */
    public static final Integer CANCEL_PREPAID = 8;

    /**
     * 9 人工补录押金（**方向固定为增加**）。
     *
     * <p>[2026-09-13 新增] 此前"给客户补一笔押金"没有任何正确的类型可用——
     * 只能借用 1(购桶入账) 或 5(预收押金)，或误用 7(异常补偿)。
     * 而 7 在 {@link #isDecrease} 里是**扣减**，`OrderBarrelExceptionServiceImpl` 却拿它做增加，
     * 于是同一个 type=7 出现两种相反的方向。现在把方向语义固化到类型上，谁都不必再猜。</p>
     */
    public static final Integer MANUAL_GRANT = 9;

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
            case 4: return "人工扣减调整";
            case 5: return "预收押金";
            case 6: return "退桶退押金";
            case 7: return "异常补偿";
            case 8: return "取消订单释放";
            case 9: return "人工补录押金";
            default: return "押金变动";
        }
    }

    /**
     * 该类型是否为「余额增加」——**方向的唯一真相源**。
     *
     * <p>约定：流水金额一律以正数传入，方向完全由类型决定；
     * 落库时增加记正、扣减记负，因此 `customer_deposit_account.balance`
     * 恒等于 `SUM(deposit_record.amount)`（对账等式1 依赖这个符号约定）。</p>
     */
    public static boolean isIncrease(Integer type) {
        if (type == null) return false;
        return type == 1 || type == 5 || type == 9;
    }

    /**
     * 该类型是否为「余额扣减」（= 除增加外的全部已知类型）。
     * <p>未知类型既不是增加也不是扣减，调用方必须**显式拒绝**，
     * 不得静默落一条不动余额的流水（旧实现就是这样把账做歪的）。</p>
     */
    public static boolean isDecrease(Integer type) {
        if (type == null) return false;
        return type == 2 || type == 3 || type == 4 || type == 6 || type == 7 || type == 8;
    }

    /** 该类型是否为"客户退回押金"（金额减少），用于生成 +/- 展示 */
    public static boolean isRefund(Integer type) {
        if (type == null) return false;
        return type == 2 || type == 6 || type == 7 || type == 8;
    }

    private DepositType() {}
}