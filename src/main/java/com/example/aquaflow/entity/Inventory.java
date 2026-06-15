package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Inventory {
    private Integer id;//库存id
    private Integer waterTypeId;//水类型
    private String waterTypeName;//水名称（关联查询）
    private String spec;//规格（关联查询）
    private Integer quantity;//数量
    private LocalDateTime updateTime;//修改时间
}
