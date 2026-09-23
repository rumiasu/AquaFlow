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

    /** 无电梯时每超一层的补贴 */
    private BigDecimal floorBonusPerLevel;

    /** 免费楼层（此层及以下不补） */
    private Integer floorFreeLevel;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 「没配」时的默认配置：全部 0，即**不产生任何收益**。
     *
     * <p>与 {@code StationDeliveryConfig.defaults} 同一个理由：存量水站都没有计件配置行，
     * 用默认值兜底可以让"没配过"与"配成全 0"在业务里根本不存在分支。</p>
     *
     * <p>⚠️ 2026-09-18（v42）起工资口径收窄为**只有两项**：送水计件 + 楼层补贴 ——
     * 原来的「回收空桶奖励」「每单固定补贴」「少收空桶扣减」三列已从库里删除，
     * 原因是站长实际只用前两项（见 {@code docs/design/18} §4.1）。少收空桶本身仍有记录
     * （订单的 {@code barrel_discrepancy} + 桶异常单），只是不再自动扣钱。</p>
     */
    public static StaffPieceRate defaults(Long stationId, Long productId) {
        StaffPieceRate r = new StaffPieceRate();
        r.setStationId(stationId);
        r.setProductId(productId != null ? productId : 0L);
        r.setPerBucketAmount(BigDecimal.ZERO);
        r.setFloorBonusPerLevel(BigDecimal.ZERO);
        r.setFloorFreeLevel(1);
        return r;
    }

    /** 本站是否配过计件（全 0 视为没配） */
    public boolean isConfigured() {
        return nz(perBucketAmount).signum() > 0 || nz(floorBonusPerLevel).signum() > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
