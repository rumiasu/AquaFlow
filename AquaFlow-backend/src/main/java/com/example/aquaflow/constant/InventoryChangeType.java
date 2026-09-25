package com.example.aquaflow.constant;

/**
 * 库存流水类型常量 [AQ-029]。
 * <p>对应 {@code inventory_record.type}，为库存变动建立可追溯的勾稽对象。</p>
 */
public class InventoryChangeType {

    /** 期初回填（上线时按当前库存量一次性回填） */
    public static final String INIT = "INIT";

    /** 入库（站长采购入库） */
    public static final String INBOUND = "INBOUND";

    /** 下单扣减（订单占用库存） */
    public static final String CONSUME = "CONSUME";

    /** 取消回补（订单取消回补已扣减库存） */
    public static final String CANCEL_RESTORE = "CANCEL_RESTORE";

    /** 退款回补（退款取消回补已扣减库存） */
    public static final String REFUND_RESTORE = "REFUND_RESTORE";

    /** 盘点调整（人工对有差异的库存做修正） */
    public static final String ADJUST = "ADJUST";

    private InventoryChangeType() {}
}
