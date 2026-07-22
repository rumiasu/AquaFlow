package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单实体类，对应数据库 orders 表。
 * <p>核心业务实体，记录客户的每一次订水请求。</p>
 * <p>状态流转：待组批(1) → 已组批(4) → 配送中(2) → 已完成(3)</p>
 */
@Data
public class Orders {

    /** 订单ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 客户名称（关联查询字段） */
    private String customerName;

    /** 客户电话（关联查询字段） */
    private String customerPhone;

    /** 配送地址ID，关联 address 表 */
    private Integer addressId;

    /** 详细地址（关联查询字段） */
    private String addressDetail;

    /** 地址标签（关联查询字段） */
    private String addressTag;

    /** 地址纬度（关联查询字段） */
    private Double addressLat;

    /** 地址经度（关联查询字段） */
    private Double addressLng;

    /** 地址收件人姓名（关联查询字段） */
    private String addressName;

    /** 地址收件人电话（关联查询字段） */
    private String addressPhone;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 水类型名称（关联查询字段） */
    private String waterTypeName;

    /** 水类型规格（关联查询字段） */
    private String waterTypeSpec;

    /** 水类型单价（关联查询字段） */
    private BigDecimal waterTypePrice;

    /** 订购数量（桶数） */
    private Integer quantity;

    /** 订单来源：1=电话, 2=微信群, 3=小程序 */
    private Integer source;

    /**
     * 订单状态：
     * <ul>
     *   <li>1 - 待组批（初始状态）</li>
     *   <li>4 - 已组批待出发</li>
     *   <li>2 - 配送中</li>
     *   <li>3 - 已完成</li>
     * </ul>
     */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;

    /** 付款状态：1=待付款 2=已付款 */
    private Integer paymentStatus;

    /** 支付方式: 1=微信 2=现金 3=水票 4=挂账 */
    private Integer paymentMethod;

    /** 结算状态：1=未结算 2=已结算 */
    private Integer settlementStatus;

    /** 应付款日期 */
    private LocalDate dueDate;

    /** 送出空桶数 */
    private Integer deliveryBucketQty;

    /** 回收空桶数 */
    private Integer returnBucketQty;

    /** 配送员ID，关联 delivery_staff 表 */
    private Long deliveryStaffId;

    /** 门卫信息 */
    private String guardInfo;

    /** 配送时间要求 */
    private String deliveryTimeRequest;

    /** 特殊说明 */
    private String specialNote;

    /** 收件人姓名快照 */
    private String receiverName;

    /** 收件人电话快照 */
    private String receiverPhone;

    /** 地址快照 */
    private String addressSnapshot;

    /** 水厂ID，关联 factory 表 */
    private Integer factoryId;

    /** 水站ID，关联 station 表 */
    private Integer stationId;
}
