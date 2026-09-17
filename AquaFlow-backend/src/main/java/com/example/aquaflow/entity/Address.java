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

    /**
     * 楼层（2026-09-17 新增，见 {@code sql/migration_v34_delivery_fee_and_floors.sql}）。
     * <p>楼层费的依据。与 {@link #hasElevator} 一样：<b>未填（NULL）时不收楼层费、只提示</b>，
     * 而不是按"无电梯"收费 —— 把未确认当无电梯会向客户乱收钱。见 {@code docs/design/17} §4.4。</p>
     */
    private Integer floor;

    /**
     * 有无电梯：<b>NULL = 未确认</b> / 0 = 确认无电梯 / 1 = 有电梯。
     *
     * <p>⚠️ NULL 与 0 <b>必须区分</b>：把 NULL 当"无电梯"会乱收费，当"有电梯"会漏收。
     * 三态是刻意的，不要用 {@code Boolean} 或 {@code boolean} 默认值把它压成两态。</p>
     */
    private Integer hasElevator;

    /** 是否默认地址: 0 非默认 1 默认 */
    private Integer isDefault;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
