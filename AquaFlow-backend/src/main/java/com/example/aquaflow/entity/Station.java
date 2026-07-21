package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水站实体类，对应数据库 station 表。
 * <p>存储水站的基本信息，包括所属水厂、站长及地址。</p>
 */
@Data
public class Station {

    /** 水站ID，主键自增 */
    private Integer id;

    /** 所属水厂ID，关联 factory 表 */
    private Integer factoryId;

    /** 水站名称 */
    private String name;

    /** 站长姓名 */
    private String manager;

    /** 站长电话 */
    private String phone;

    /** 水站地址 */
    private String address;

    /** 状态：0=停用 1=启用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
