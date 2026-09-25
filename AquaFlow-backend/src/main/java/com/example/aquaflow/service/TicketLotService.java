package com.example.aquaflow.service;

import com.example.aquaflow.entity.TicketLot;

import java.math.BigDecimal;

/**
 * 水票批次账（v36）：水票余额的真相源与单价快照的唯一维护入口。规格见 {@code docs/design/19}。
 *
 * <p><b>本服务是水票批次的唯一写入口</b> —— 与桶账的 {@code BarrelLedgerService} 同一角色。
 * 任何直接改 {@code ticket_lot} 或 {@code ticket_account.right_amount} 的代码都是绕过账本，
 * 对账 E8 会立刻报不平。</p>
 *
 * <p>三条不变量：</p>
 * <ol>
 *   <li>{@code ticket_account.remain_quantity == Σ ticket_lot.remain_qty}（status=1）</li>
 *   <li>{@code ticket_account.right_amount == Σ remain_qty × unit_price}（status=1）</li>
 *   <li>消耗按 <b>FIFO</b>（先买先扣）；单价一律取批次快照，<b>绝不按当前价重算</b> ——
 *       站长改价不能改到客户已经付过的钱</li>
 * </ol>
 */
public interface TicketLotService {

    /**
     * 建批次（入账）。所有"水票变多"的路径都走这里：在线购票确认、站长加票、退款回补、历史迁移。
     *
     * @param unitPrice        单价快照。在线购票传<b>实付均价</b>（档位价 ÷ 张数），这是最可信的来源
     * @param sourceType       见 {@link TicketLot.SourceType}
     * @param priceSource      见 {@link TicketLot.PriceSource}
     * @param priceInferred    true = 单价是推断出来的，退票需二次确认
     * @param paymentRecordId  来源支付流水（在线购票时传，其它为 null）
     * @return 已生成正式批次号的批次
     */
    TicketLot createLot(Long customerId, Long stationId, Long productId, BigDecimal unitPrice, int qty,
                        int sourceType, int priceSource, boolean priceInferred,
                        Long paymentRecordId, String note);

    /**
     * 按 FIFO 消耗批次（出账）。同时把消耗金额回传给调用方写进流水，
     * 这样将来退款回补能按**当时的加权均价**还原，而不是按退款时的当前价。
     *
     * @throws com.example.aquaflow.exception.BusinessException 批次余额不足时（正常流程不该发生：
     *         调用方应先用 {@code ticket_account.remain_quantity} 校验并扣减）
     */
    ConsumeResult consumeFifo(Long customerId, Long stationId, Long productId, int qty);

    /** 按批次重算并同步 {@code ticket_account.right_amount}（派生列） */
    void refreshRightAmount(Long customerId, Long stationId, Long productId);

    /** FIFO 消耗结果 */
    class ConsumeResult {
        /** 本次消耗的总金额 = Σ 取用张数 × 该批次单价 */
        private BigDecimal totalAmount = BigDecimal.ZERO;
        /** 加权平均单价（跨批次时用它记流水；单价带 4 位小数避免反复四舍五入失真） */
        private BigDecimal weightedUnitPrice = BigDecimal.ZERO;
        /** 只消耗了一个批次时记该批次 id；跨批次为 null */
        private Long singleLotId;

        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        public BigDecimal getWeightedUnitPrice() { return weightedUnitPrice; }
        public void setWeightedUnitPrice(BigDecimal weightedUnitPrice) { this.weightedUnitPrice = weightedUnitPrice; }
        public Long getSingleLotId() { return singleLotId; }
        public void setSingleLotId(Long singleLotId) { this.singleLotId = singleLotId; }
    }
}
