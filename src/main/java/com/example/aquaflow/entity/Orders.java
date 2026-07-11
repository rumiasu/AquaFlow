package com.example.aquaflow.entity;

import lombok.Data;

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

    /** 配送地址ID，关联 address 表 */
    private Integer addressId;

    /** 详细地址（关联查询字段） */
    private String addressDetail;

    /** 地址标签（关联查询字段） */
    private String addressTag;

    /** 水类型ID，关联 water_type 表 */
    private Integer waterTypeId;

    /** 水类型名称（关联查询字段） */
    private String waterTypeName;

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
}
