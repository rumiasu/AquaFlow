package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 站级配送计费配置，对应 {@code station_delivery_config} 表（一行一个水站），
 * 2026-09-17 新增（v35）。规格见 {@code docs/design/17} §3.1。
 *
 * <p><b>只存经营参数，不存计算结果</b> —— 算出来的费用落在 {@code orders.delivery_fee} /
 * {@code orders.floor_fee} 上（下单时快照），因为站长改配置不能改到历史订单的金额。</p>
 *
 * <p><b>「没配」= 不收费、不拦单</b>：{@link #defaults(Long)} 给出一份全 0 的配置，
 * 所以「该站没有配置行」与「配置全是 0」行为完全一致，不需要在业务里到处判 null。
 * 这一条很重要 —— 本表是后加的，存量水站全都没有配置行，它们的下单行为必须**一个字都不变**。</p>
 */
@Data
public class StationDeliveryConfig {

    /** 水站ID（主键，一站一行） */
    private Long stationId;

    /**
     * 起送量：桶数与金额**取或**（满足其一即达门槛）。两个都为空 = 不限起送量。
     */
    private Integer minOrderBuckets;

    /** 起送金额（水费口径，不含押金与运费） */
    private BigDecimal minOrderAmount;

    /** 未达起送量的处理方式，见 {@link com.example.aquaflow.constant.DeliveryLimitMode}。默认 WARN */
    private String minOrderMode;

    /** {@code FEE} 模式下未达起送量时的加收金额 */
    private BigDecimal minOrderFee;

    /** 配送半径（米）。{@code null} = 不限范围 */
    private Integer deliveryRadiusM;

    /** 超出半径的处理方式，见 {@link com.example.aquaflow.constant.DeliveryLimitMode}。默认 WARN */
    private String overRadiusMode;

    /** {@code FEE} 模式下超范围的加收金额 */
    private BigDecimal remoteFee;

    /** 基础配送费（不满足免运费门槛时收取） */
    private BigDecimal baseDeliveryFee;

    /** 免运费门槛：桶数与水费金额取或。两个都为空 = 没有免运费门槛（即一直收基础配送费） */
    private Integer freeDeliveryBuckets;

    /** 免运费金额门槛（水费口径） */
    private BigDecimal freeDeliveryAmount;

    /** 免费楼层（此层及以下不收楼层费）。默认 1 */
    private Integer floorFreeLevel;

    /** 每超一层加收金额 */
    private BigDecimal floorFeePerLevel;

    /** 楼层费口径，见 {@link com.example.aquaflow.constant.FloorFeeMode}。默认 PER_ORDER */
    private String floorFeeMode;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 「没配」时的默认配置：不收费、不拦单、只提示。
     *
     * <p>用默认值而不是在业务层判 null，是为了让"存量水站没有配置行"这件事在
     * {@code DeliveryFeeUtil} 里根本不存在分支 —— 少一个分支就少一处口径分叉。</p>
     */
    public static StationDeliveryConfig defaults(Long stationId) {
        StationDeliveryConfig c = new StationDeliveryConfig();
        c.setStationId(stationId);
        c.setMinOrderMode(com.example.aquaflow.constant.DeliveryLimitMode.WARN);
        c.setOverRadiusMode(com.example.aquaflow.constant.DeliveryLimitMode.WARN);
        c.setMinOrderFee(BigDecimal.ZERO);
        c.setRemoteFee(BigDecimal.ZERO);
        c.setBaseDeliveryFee(BigDecimal.ZERO);
        c.setFloorFreeLevel(1);
        c.setFloorFeePerLevel(BigDecimal.ZERO);
        c.setFloorFeeMode(com.example.aquaflow.constant.FloorFeeMode.PER_ORDER);
        return c;
    }
}
