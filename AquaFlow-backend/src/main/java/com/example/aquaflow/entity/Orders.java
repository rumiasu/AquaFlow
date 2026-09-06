package com.example.aquaflow.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体类，对应数据库 orders 表。
 * <p>这是整个系统核心。</p>
 */
@Data
public class Orders {

    /** 订单ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 客户名称(关联查询字段) */
    private String customerName;

    /** 客户电话(关联查询字段) */
    private String customerPhone;

    /** 地址ID */
    private Long addressId;

    /** 地址详情(关联查询字段) */
    private String addressDetail;

    /** 订单归属水站 */
    private Long stationId;

    /** 实际履约配送水站ID，可与 station_id 不同 */
    private Long deliveryStationId;

    /** 配送员ID */
    private Long deliveryStaffId;

    /** 配送员姓名(关联查询字段) */
    private String deliveryStaffName;

    /** 订单总数量（所有商品数量之和） */
    private Integer quantity;

    /** 来源: 1 电话 2 微信 3 小程序 */
    private Integer source;

    /** 订单状态（canonical 1-5）：1 待配送 2 配送中 3 已送达 4 已完成 5 已取消（是否分配用 delivery_staff_id 判断） */
    private Integer status;

    /** 支付方式: 1 微信支付 2 水票 3 线下支付 */
    private Integer paymentMethod;

    /** 支付状态: 1 待付款 2 已付款 */
    private Integer paymentStatus;

    /** 结算状态: 1 未结算 2 已结算 */
    private Integer settlementStatus;

    /** 应结算日期 */
    private java.time.LocalDate dueDate;

    /** 批次ID */
    private Long batchId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 水费金额 */
    private BigDecimal waterAmount;

    /** 押金金额 */
    private BigDecimal depositAmount;

    /** 收件人姓名 */
    private String receiverName;

    /** 收件人电话 */
    private String receiverPhone;

    /** 地址快照 */
    private String addressSnapshot;

    /** 地址快照纬度 */
    private BigDecimal addressSnapshotLat;

    /** 地址快照经度 */
    private BigDecimal addressSnapshotLng;

    /** 门卫信息 */
    private String guardInfo;

    /** 配送时间要求 */
    private String deliveryTimeRequest;

    /** 特殊说明 */
    private String specialNote;

    /** 配送桶数量 */
    private Integer deliveryBucketQty;

    /** 回收桶数量 */
    private Integer returnBucketQty;

    /** 差桶数量 */
    private Integer barrelDiscrepancy;

    /** 差桶差异说明 */
    private String barrelDiscrepancyNote;

    /** 是否有异常标记 */
    private Boolean exceptionFlag;

    /** 是否首次桶装水订单（押金桶无需回桶） */
    private Boolean firstBarrelOrder;

    /** 首个异常类别 */
    private String exceptionCategory;

    /** 异常次数 */
    private Integer exceptionCount;

    /** 关联的桶异常记录ID */
    private Long barrelExceptionId;

    /** 幂等键：防止重复下单 */
    private String idempotencyKey;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 首个商品名称(列表联查字段) */
    private String firstProductName;

    /** 订单商品明细(关联查询字段) */
    private List<OrderItem> items;
}
