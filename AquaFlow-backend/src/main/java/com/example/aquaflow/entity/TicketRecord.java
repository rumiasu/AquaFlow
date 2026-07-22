package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水票变动记录实体类，对应数据库 ticket_record 表。
 * <p>记录客户水票的增减明细，包括购买、使用、赠送等来源。</p>
 */
@Data
public class TicketRecord {

    /** 记录ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 增加数量 */
    private Integer increaseQty;

    /** 减少数量 */
    private Integer decreaseQty;

    /** 关联订单ID，可为空 */
    private Integer orderId;

    /** 来源说明，如"购买"、"使用"、"赠送" */
    private String source;

    /** 水票来源渠道：1=线上购买 2=线下购买 */
    private Integer ticketSource;

    /** 创建时间 */
    private LocalDateTime createTime;
}
