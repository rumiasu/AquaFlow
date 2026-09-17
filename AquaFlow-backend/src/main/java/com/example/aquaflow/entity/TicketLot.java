package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水票批次（单价快照），对应 {@code ticket_lot} 表（v36）。规格见 {@code docs/design/19}。
 *
 * <p><b>照抄 {@code customer_barrel_lot} 的模型，不另发明一套。</b>
 * 桶账早就在解决同一个问题：档位意味着票价分段，站长改了档位价之后，
 * 「客户账户里已买的票值多少钱」与「退票按什么价退」就无从回答。
 * 押金条给的答案是记下买入当时单价 —— "2026 年 30 元买的，2027 年退就退 30 元"。</p>
 *
 * <p>真相源是 {@link #remainQty} 之和：{@code ticket_account.remain_quantity} 是它的派生汇总，
 * {@code ticket_account.right_amount} 是 {@code Σ remainQty × unitPrice}。后者由对账 E8 校验。</p>
 */
@Data
public class TicketLot {

    private Long id;

    /** 批次号 TMyyyymmdd-000001 */
    private String lotNo;

    private Long customerId;

    private Long stationId;

    private Long productId;

    /**
     * 买入当时单价快照（= 档位均价，或散买的单张票价）。
     * <b>退票按它退，不按退时的当前价</b> —— 否则站长改价会改到客户已经付过的钱。
     */
    private BigDecimal unitPrice;

    /** 本批张数 */
    private Integer qty;

    /** 剩余未退张数 */
    private Integer remainQty;

    /** 1 在线购买 / 2 历史迁移 / 3 人工补录 / 4 退款回补，见 {@link SourceType} */
    private Integer sourceType;

    /** 1 实付均价 / 2 当时站级水票价 / 3 当前价推断（兜底），见 {@link PriceSource} */
    private Integer priceSource;

    /** 1 = 历史迁移或单价为推断值，退票需二次确认 */
    private Integer isMigrated;

    /** 来源支付流水（在线购买时） */
    private Long paymentRecordId;

    /** 1 有效 / 2 已退完 / 3 作废 */
    private Integer status;

    private Long operatorId;

    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 单价是否为推断值（迁移/兜底），退票前要二次确认 */
    public boolean isPriceInferred() {
        return isMigrated != null && isMigrated == 1;
    }

    /** 剩余张数的金额价值 */
    public BigDecimal getRightAmount() {
        if (unitPrice == null || remainQty == null) return BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(remainQty));
    }

    /**
     * 批次来源。
     *
     * <p>与桶账的 {@code BarrelLedgerService.LotOrigin} 对齐 —— 三种来源的单价可信度不同，
     * 会直接影响退款是否需要二次确认，所以必须落库而不是靠调用方自觉。</p>
     */
    public static final class SourceType {
        /** 在线购买（单价 = 实付均价，最可信） */
        public static final int PURCHASE = 1;
        /** 历史迁移（单价为推断值） */
        public static final int MIGRATION = 2;
        /** 人工补录（站长加票等） */
        public static final int MANUAL = 3;
        /** 退款回补（订单取消把票还回来，单价取当次消耗的加权均价） */
        public static final int REFUND_RESTORE = 4;

        private SourceType() {}
    }

    /** 价格来源，见 {@link #priceSource} */
    public static final class PriceSource {
        /** 订单/购票实付均价 */
        public static final int PAID = 1;
        /** 当时站级水票价 */
        public static final int STATION_TICKET_PRICE = 2;
        /** 当前价推断（兜底，不可信） */
        public static final int INFERRED = 3;

        private PriceSource() {}
    }
}
