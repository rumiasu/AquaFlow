package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 站级配送计件单价，对应 {@code staff_piece_rate} 表（v37）。规格见 {@code docs/design/18}。
 *
 * <p>{@code productId = 0} 表示<b>该站默认价</b>；非 0 表示某商品的专属单价
 * （18.9L 与 5L 的搬运成本不同，所以计价粒度按商品）。</p>
 *
 * <p>⚠️ 用 0 而不是 NULL 表达"默认"：主键列不能为 NULL，而复合唯一键里含 NULL 会退化成
 * "永不冲突"（本仓在 {@code uk_ticket_consume} 上踩过这个坑）。</p>
 */
@Data
public class StaffPieceRate {

    private Long stationId;

    /** 商品ID；0 = 该站默认价 */
    private Long productId;

    /** 每送一桶的计件价；0 = 本站不计件（不是"免费"，是"不参与计件"） */
    private BigDecimal perBucketAmount;

    /** 每回收一个空桶的奖励 */
    private BigDecimal returnBucketAmount;

    /** 无电梯时每超一层的补贴 */
    private BigDecimal floorBonusPerLevel;

    /** 免费楼层（此层及以下不补） */
    private Integer floorFreeLevel;

    /** 每单基础奖励 */
    private BigDecimal perOrderAmount;

    /** 每少收一个空桶的扣减 */
    private BigDecimal penaltyPerBucket;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 「没配」时的默认配置：全部 0，即**不产生任何收益**。
     *
     * <p>与 {@code StationDeliveryConfig.defaults} 同一个理由：存量水站都没有计件配置行，
     * 用默认值兜底可以让"没配过"与"配成全 0"在业务里根本不存在分支。</p>
     */
    public static StaffPieceRate defaults(Long stationId, Long productId) {
        StaffPieceRate r = new StaffPieceRate();
        r.setStationId(stationId);
        r.setProductId(productId != null ? productId : 0L);
        r.setPerBucketAmount(BigDecimal.ZERO);
        r.setReturnBucketAmount(BigDecimal.ZERO);
        r.setFloorBonusPerLevel(BigDecimal.ZERO);
        r.setFloorFreeLevel(1);
        r.setPerOrderAmount(BigDecimal.ZERO);
        r.setPenaltyPerBucket(BigDecimal.ZERO);
        return r;
    }

    /** 本站是否配过计件（全 0 视为没配） */
    public boolean isConfigured() {
        return nz(perBucketAmount).signum() > 0 || nz(returnBucketAmount).signum() > 0
                || nz(floorBonusPerLevel).signum() > 0 || nz(perOrderAmount).signum() > 0
                || nz(penaltyPerBucket).signum() > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
