package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金流水实体类，对应数据库 deposit_record 表。
 * <p>记录客户押金的新增、退还、赔偿等变动。</p>
 */
@Data
public class DepositRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 所属水站ID */
    private Long stationId;

    /** 涉及商品ID：桶权益按 (customer, station, product) 隔离，流水必须带商品维度 */
    private Long productId;

    /** 类型: 1 新增押金 2 退押金 3 丢桶赔偿 4 其他调整 */
    private Integer type;

    /** 金额 */
    private BigDecimal amount;

    /** 本次桶权益的买入单价快照（退款只认批次单价，此字段供对账/审计） */
    private BigDecimal unitPrice;

    /** 本次涉及桶数 */
    private Integer quantity;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 备注 */
    private String note;

    /** 操作员ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /**
     * 站长资产调整单 ID（station_adjustment.id），NULL=非调整产生。
     * <p>uk_deposit_adjustment 的幂等依据：一张调整单最多一条押金流水。</p>
     */
    private Long adjustmentId;

    /* ==================== 派生文案（只读，随序列化下发给前端） ====================
     * 与 PaymentRecord.getMethodText()/getStatusText()、Orders.getStatusText() 同一处理方式。
     * [2026-09-18 补] 顾客端要上线「押金流水」页（终于能看到"余额为什么变"），
     * 而本实体只下发数字 type —— 前端若自己写 type→中文 的映射表，就重演了
     * "两端各写一套、后端调口径前端不跟随"的历史事故（PayMethod 那条注释记的就是它）。
     * 文案的唯一真相源是 {@link com.example.aquaflow.constant.DepositType#textOf(Integer)}。
     */

    /** 押金变动类型文案（1 押金入账 / 2 退押金 / … / 9 人工补录押金），真相源是 DepositType */
    public String getTypeText() {
        return com.example.aquaflow.constant.DepositType.textOf(type);
    }
}
