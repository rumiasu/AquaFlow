package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class Batch {
    private Integer id;//批次id
    private Integer status;//状态:1待出发,2配送中,3已完成
    private Integer totalQTY;//总数量
    private Integer stationId;//所属水站ID
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
    
    // 关联查询字段
    private List<Orders> orders;//批次包含的订单列表
}
