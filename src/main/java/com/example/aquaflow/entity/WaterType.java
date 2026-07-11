package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水类型实体类，对应数据库 water_type 表。
 * <p>维护桶装水的品类信息，库存和订单均引用此表。例如"农夫山泉18.9L"。</p>
 */
@Data
public class WaterType {

    /** 水类型ID，主键自增 */
    private Integer id;

    /** 水类型名称，如"农夫山泉"、"娃哈哈" */
    private String name;

    /** 规格容量，如"18.9L"、"11.3L" */
    private String spec;

    /** 备注信息 */
    private String note;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后修改时间 */
    private LocalDateTime updateTime;
}
