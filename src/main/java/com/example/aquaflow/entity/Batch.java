package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Batch {
    private Integer id;//批次id
    private Integer status;//状态:1待出发,2配送中,3已完成
    private Integer totalQTY;//总数量
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
}
