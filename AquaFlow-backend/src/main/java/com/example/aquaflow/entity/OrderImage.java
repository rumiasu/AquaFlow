package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderImage {
    private Integer id;
    private Integer orderId;
    /** COS 对象键（如 private/order/42/abc.jpg） */
    private String objectName;
    private Integer type; // 1=正常 2=异常
    private LocalDateTime createTime;

    /** 临时访问 URL（由 Controller 注入，不入库） */
    private transient String url;
}
