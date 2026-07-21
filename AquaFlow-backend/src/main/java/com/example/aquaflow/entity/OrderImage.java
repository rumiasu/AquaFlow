package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderImage {
    private Integer id;//订单图片
    private Integer orderId;//订单id
    private String url;
    private Integer type;//类型1正常2异常
    private LocalDateTime createTime;//创建时间
}
