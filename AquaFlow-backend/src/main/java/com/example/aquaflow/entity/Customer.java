package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
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

    /** 所属水站ID，关联 station 表 */
    private Integer stationId;

    /** 负责配送员ID，关联 staff 表 */
    private Integer ownerStaffId;

    /** 绑定时间 */
    private LocalDateTime bindTime;

    /** 绑定原因 */
    private String bindReason;

    /** 最近配送时间 */
    private LocalDateTime lastDeliveryTime;

    /** 押金余额 */
    private BigDecimal depositBalance;

    /** 客户类型：1=个人 2=企业 */
    private Integer customerType;

    /** 首次下单时间 */
    private LocalDateTime firstOrderTime;

    /** 累计订单数 */
    private Integer totalOrders;

    /** 累计消费金额 */
    private BigDecimal totalConsumption;

    /** 平均配送周期（天） */
    private Integer avgCycleDays;

    /** 标签，多个标签以逗号分隔 */
    private String tags;

    /** 微信openid，用于小程序登录 */
    private String openid;

    /** 角色: 1=站长 2=管理员 */
    private Integer role;
}
