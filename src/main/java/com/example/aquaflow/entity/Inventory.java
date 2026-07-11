package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存实体类，对应数据库 inventory 表。
 * <p>记录每种水类型的库存数量。支持入库累加、组批扣减操作。</p>
 */
@Data
public class Inventory {

    /** 库存记录ID，主键自增 */
    private Integer id;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 水类型名称（关联查询字段，非库存表本身字段） */
    private String waterTypeName;

    /** 规格信息（关联查询字段，非库存表本身字段） */
    private String spec;

    /** 当前库存数量 */
    private Integer quantity;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
