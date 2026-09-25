package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户「配送中」桶资产实体类，对应数据库 customer_barrel_in_transit 表。
 *
 * <h3>术语（全系统统一，勿再写成"在途"）</h3>
 * <pre>
 *   配送中 = 顾客已付款买下桶权益、但桶还没送到顾客手上
 * </pre>
 * 这是<b>权益已成交、实物未交付</b>的中间态：钱收了，权益还没进
 * {@link CustomerBarrelAsset}。送达后由 BarrelLedgerService 生成押金条
 * {@link CustomerBarrelLot} 并把权益转正。
 *
 * <p>⚠️ <b>类名与表名沿用历史命名 in_transit，只为避免改表成本，不代表对外术语。</b>
 * 注释、日志、接口文案一律写「配送中」，不要写「在途」。</p>
 *
 * <h3>状态流转（status 为英文枚举，别改，改动面太大）</h3>
 * <pre>
 *   PENDING   配送中：已购待送，顾客尚未收到
 *   DELIVERED 已送达：权益已转为押金条，记录保留可追溯（不再物理删除）
 *   CANCELLED 已取消：订单取消，权益未成立
 * </pre>
 */
@Data
public class CustomerBarrelInTransit {

    /** ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 产品ID(桶装水) */
    private Long productId;

    /** 配送中桶数 = 本单新购权益数（下单时算出的 shortage） */
    private Integer qty;

    /**
     * 下单当时的桶权益单价快照。配送完成时用它建 customer_barrel_lot.unit_price，
     * 保证「按买入时价格退款」——不能用配送时的当前商品押金价。
     */
    private java.math.BigDecimal unitPrice;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 状态: PENDING=配送中 / DELIVERED=已送达 / CANCELLED=已取消（术语见类注释） */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}