package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水站间调拨记录
 */
@Data
public class StockTransfer {

    private Integer id;

    /** 调出水站ID */
    private Integer fromStationId;

    /** 调入水站ID */
    private Integer toStationId;

    /** 水类型ID */
    private Integer waterTypeId;

    /** 调拨数量 */
    private Integer quantity;

    /** 状态: 1=待审批 2=已审批 3=已完成 4=已取消 */
    private Integer status;

    /** 审批备注 */
    private String approveNote;

    /** 完成备注 */
    private String completeNote;

    /** 调出水站名称（关联查询） */
    private String fromStationName;

    /** 调入水站名称（关联查询） */
    private String toStationName;

    /** 水类型名称（关联查询） */
    private String waterTypeName;

    /** 水类型规格（关联查询） */
    private String waterTypeSpec;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
