package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Address {
    private Integer id;//地址id
    private String detail;//详细地址
    private String tag;//团块划分
    private Double lat;//地图纬度
    private Double lng;//地图经度
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
}
