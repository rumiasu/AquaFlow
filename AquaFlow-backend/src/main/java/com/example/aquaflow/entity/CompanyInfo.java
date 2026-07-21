package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业客户信息实体类，对应数据库 company_info 表。
 * <p>存储企业客户的公司名称、联系人、付款方式等扩展信息。</p>
 */
@Data
public class CompanyInfo {

    /** 记录ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 公司名称 */
    private String companyName;

    /** 联系人姓名 */
    private String contactPerson;

    /** 联系人电话 */
    private String contactPhone;

    /** 付款方式，如"月结"、"现结" */
    private String paymentMethod;

    /** 账期天数 */
    private Integer dueDays;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
