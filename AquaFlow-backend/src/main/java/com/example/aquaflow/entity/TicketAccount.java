package com.example.aquaflow.entity;

import lombok.Data;

/**
 * 水票账户实体类，对应数据库 ticket_account 表。
 * <p>记录客户按水类型维度的水票剩余数量。</p>
 */
@Data
public class TicketAccount {

    /** 账户ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 剩余水票数量 */
    private Integer remainQuantity;
}
