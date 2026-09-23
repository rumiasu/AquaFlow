package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体类，对应数据库 payment_record 表。
 * <p>记录订单的支付信息。</p>
 */
@Data
public class PaymentRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /**
     * 客户端幂等键（在线购票等<b>无订单支付</b>用），NULL = 不参与防重。
     *
     * <p>为什么必须有：无订单支付的 {@code order_id} 为 NULL，而数据库层的
     * {@code uk_payment_active_order} 建在生成列 {@code active_order_id}
     * （{@code case when status in (1,2) then order_id else null end}）上，
     * order_id 为 NULL 时该生成列同样是 NULL —— <b>MySQL 唯一键中 NULL 互不冲突</b>，
     * 于是这条路径零保护，连点两次就产生两条待收款流水，站长两次确认即入账两次。</p>
     *
     * <p>唯一键是 {@code uk_payment_idempotency(customer_id, idempotency_key)}：
     * 必须带 customer_id，否则客户端传别人的 token 会拿回别人的支付记录。</p>
     */
    private String idempotencyKey;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 支付金额 */
    private BigDecimal amount;

    /**
     * 在线购买水票：所购水票标识（对应数据库 payment_record.ticket_water_type_id 列）。
     * 用于支付确认后自动入账水票，非购票支付为 null。
     */
    private Long ticketWaterTypeId;

    /** 在线购买水票：购买数量 */
    private Integer ticketQty;

    /**
     * 在线购票：所购档位（{@code ticket_package.id}，v36）。
     *
     * <p>为什么要落库：档位价会变。历史流水若只记「买了 100 张、收了 800 元」，
     * 几个月后无法自证当时是哪个档位，也就无法解释"为什么这 100 张均价 8 元而现在均价 9 元"。</p>
     */
    private Long ticketPackageId;

    /** 水费金额 */
    private BigDecimal waterAmount;

    /** 桶押金金额 */
    private BigDecimal barrelDeposit;

    /**
     * 配送费 / 楼层费（2026-09-17 新增，见 {@code sql/migration_v34_delivery_fee_and_floors.sql}）。
     *
     * <p>与 {@code orders.delivery_fee} / {@code orders.floor_fee} <b>同口径</b>，
     * 供对账等式2（订单支付状态与流水是否相符）比对 —— 两边口径不一致会报
     * 「有凭证未置已付」。当前恒为 0（Phase 0 只加列不计算）。</p>
     */
    private BigDecimal deliveryFee;

    /** 楼层费，语义同 {@link #deliveryFee} */
    private BigDecimal floorFee;

    /** 超出桶数 */
    private Integer excessBarrels;

    /**
     * 支付方式：1 微信 2 现金（货到付款） 3 水票。
     * 以 {@link com.example.aquaflow.constant.PayMethod} 为准（本注释此前误写为 "2水票 3线下"）。
     */
    private Integer paymentMethod;

    /** 状态: 1 待支付 2 已支付 3 已退款 4 已取消 */
    private Integer status;

    /** 交易流水号 */
    private String transactionNo;

    /** 操作员ID(配送员确认线下支付) */
    private Long operatorId;

    /** 备注 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /* ==================== 派生文案（只读，随序列化下发给前端） ====================
     * 前端禁止自行维护「支付方式/支付状态 → 文案」的映射表：历史上两端各写一套，
     * 后端调整口径后前端不跟随，展示与实际状态不符。文案一律由后端下发。
     * 与本项目 Orders.getStatusText()/getPayMethodText() 的处理方式一致。
     */

    /** 支付方式文案（1 微信 / 2 现金(货到付款) / 3 水票），真相源是 PayMethod */
    public String getMethodText() {
        return com.example.aquaflow.constant.PayMethod.textOf(paymentMethod);
    }

    /** 支付状态文案（0 未支付 / 1 待收款 / 2 已付款 / 3 已退款 / 4 已取消），真相源是 PaymentStatus */
    public String getStatusText() {
        return com.example.aquaflow.constant.PaymentStatus.textOf(status);
    }
}
