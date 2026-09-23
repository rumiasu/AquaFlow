package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业资料实体类，对应数据库 company_info 表。
 */
@Data
public class CompanyInfo {

    private Long id;

    private Long customerId;

    private String companyName;

    private String contactPerson;

    private String contactPhone;

    private String paymentMethod;

    private Integer dueDays;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
