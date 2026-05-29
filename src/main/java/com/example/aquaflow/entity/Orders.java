package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Orders {
    private Integer id;//订单id
    private Integer customerId;//客户id
    private Integer addressId;//地址id
    private Integer waterTypeId;//水类型id
    private Integer quantity;//数量
    private Integer source;//来源1电话2微信群3小程序
    private Integer status;//状态1待配送2配送中3已完成
    private LocalDateTime createTime;//创建时间
    private LocalDateTime updateTime;//修改时间
}
