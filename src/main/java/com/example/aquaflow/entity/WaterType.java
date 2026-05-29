package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WaterType {
    private Integer id;//水类型id
    private String name;//水类型名称
    private String spec;//规格容量
    private String note;//备注
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
}
