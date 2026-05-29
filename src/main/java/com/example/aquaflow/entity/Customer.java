package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Customer {
    private Integer id;//客户id
    private String name;//客户名
    private String phone;//手机号
    private String note;//备注
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
}
