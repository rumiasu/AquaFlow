package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水票流水实体类，对应数据库 ticket_record 表。
 * <p>记录客户水票的增减明细。</p>
 */
@Data
public class TicketRecord {

    /** 记录ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水) */
    private Long productId;

    /** 所属水站ID */
    private Long stationId;

    /** 增加数量 */
    private Integer increaseQty;

    /** 减少数量 */
    private Integer decreaseQty;

    /** 关联订单ID */
    private Long orderId;

    /** 来源说明 */
    private String source;

    /** 水票来源: 1 线上 2 线下 */
    private Integer ticketSource;

    /** 创建时间 */
    private LocalDateTime createTime;
}
