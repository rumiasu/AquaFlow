package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Inventory {
    private Integer id;//库存id
    private Integer waterTypeId;//水类型
    private Integer quantity;//数量
    private LocalDateTime updateTime;//修改时间
}
