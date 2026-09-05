package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水票账户实体类，对应数据库 ticket_account 表。
 * <p>记录客户按商品维度的水票剩余数量。</p>
 */
@Data
public class TicketAccount {

    /** 账户ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 商品ID(桶装水) */
    private Long productId;

    /** 所属水站ID */
    private Long stationId;

    /** 剩余水票数量 */
    private Integer remainQuantity;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
