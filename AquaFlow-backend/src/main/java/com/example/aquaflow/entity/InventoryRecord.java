package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水实体类，对应数据库 inventory_record 表 [AQ-029]。
 * <p>记录每一次库存变动（入库 / 下单扣减 / 取消回补 / 退款回补 / 盘点调整），
 * 使 {@code inventory.quantity} 有勾稽对象：流水累计应等于当前库存。</p>
 */
@Data
public class InventoryRecord {

    /** 流水ID，主键自增 */
    private Long id;

    /** 水站ID */
    private Long stationId;

    /** 商品ID */
    private Long productId;

    /** 库存变动量：正=入库/回补，负=消耗/出库 */
    private Integer delta;

    /** 变动类型，见 {@link com.example.aquaflow.constant.InventoryChangeType} */
    private String type;

    /** 关联单据ID（订单ID等） */
    private Long refId;

    /** 操作人员工ID */
    private Long operatorId;

    /** 备注 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 商品名称（关联查询字段） */
    private String productName;

    /** 规格（关联查询字段） */
    private String spec;
}
