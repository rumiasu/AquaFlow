package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BatchOrder {
    private Integer id;//批次订单关系id
    private Integer batchId;//批次id
    private Integer orderId;//订单id
    private LocalDateTime createTime;//创建时间
}
