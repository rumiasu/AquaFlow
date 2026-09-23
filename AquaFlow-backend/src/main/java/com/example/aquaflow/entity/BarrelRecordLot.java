package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退桶（终止权益）时的权益批次核销明细，对应表 barrel_record_lot。
 *
 * <p>一条 barrel_record（type=2 退桶）会按 FIFO 核销一个或多个
 * {@link CustomerBarrelLot}，每个批次一行，记录当时的核销单价。
 * 这样退款金额可复现、可审计：refund = Σ qty_i × unit_price_i。</p>
 *
 * <p><b>纯还桶（type=7）不写本表</b>——它不消耗权益，只冲减 over，不产生退款。</p>
 */
@Data
public class BarrelRecordLot {

    private Long id;

    /** barrel_record.id */
    private Long recordId;

    /** customer_barrel_lot.id */
    private Long lotId;

    /** 本次从该批次核销的权益数 */
    private Integer qty;

    /** 核销时的批次单价快照 */
    private BigDecimal unitPrice;

    /** qty × unit_price */
    private BigDecimal amount;
}
