package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 地址实体类，对应数据库 address 表。
 * <p>存储配送地址信息，支持按标签（如小区、工厂）分类，预留经纬度用于地图功能。</p>
 */
@Data
public class Address {

    /** 地址ID，主键自增 */
    private Integer id;

    /** 详细地址，如"XX小区1号楼101" */
    private String detail;

    /** 地址标签，用于区域划分，如"小区"、"工厂" */
    private String tag;

    /** 纬度，预留用于地图选点 */
    private Double lat;

    /** 经度，预留用于地图选点 */
    private Double lng;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
