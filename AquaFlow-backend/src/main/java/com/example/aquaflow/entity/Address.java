package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 地址实体类，对应数据库 address 表。
 * <p>detail 承担：完整收货地址（小区+楼栋+单元+门牌号）。</p>
 */
@Data
public class Address {

    /** 地址ID，主键自增 */
    private Long id;

    /** 所属客户ID */
    private Long customerId;

    /** 收件人姓名 */
    private String name;

    /** 收件人电话 */
    private String phone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 标签(家/公司/父母家) */
    private String label;

    /** 详细地址(完整收货地址) */
    private String detail;

    /** 纬度 */
    private BigDecimal lat;

    /** 经度 */
    private BigDecimal lng;

    /** 是否默认地址: 0 非默认 1 默认 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
