package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户实体类，对应数据库 customer 表。
 * <p>存储桶装水配送站的客户基本信息，包括姓名、手机号和备注。</p>
 */
@Data
public class Customer {

    /** 客户ID，主键自增 */
    private Integer id;

    /** 客户姓名 */
    private String name;

    /** 客户手机号，用于联系配送 */
    private String phone;

    /** 备注信息，如配送时间要求等 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
