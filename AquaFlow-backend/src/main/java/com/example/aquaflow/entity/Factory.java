package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水厂实体类，对应数据库 factory 表。
 * <p>存储水厂的基本信息，包括联系人和地址。</p>
 */
@Data
public class Factory {

    /** 水厂ID，主键自增 */
    private Integer id;

    /** 水厂名称 */
    private String name;

    /** 联系人姓名 */
    private String contactPerson;

    /** 联系人电话 */
    private String contactPhone;

    /** 水厂地址 */
    private String address;

    /** 状态：0=停用 1=启用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
