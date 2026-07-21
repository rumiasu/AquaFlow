package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户换站记录实体类，对应数据库 customer_station_record 表。
 * <p>记录客户从一个水站转移到另一个水站的变更历史。</p>
 */
@Data
public class CustomerStationRecord {

    /** 记录ID，主键自增 */
    private Integer id;

    /** 客户ID，关联 customer 表 */
    private Integer customerId;

    /** 原水站ID，关联 station 表 */
    private Integer fromStationId;

    /** 目标水站ID，关联 station 表 */
    private Integer toStationId;

    /** 换站原因 */
    private String reason;

    /** 创建时间 */
    private LocalDateTime createTime;
}
