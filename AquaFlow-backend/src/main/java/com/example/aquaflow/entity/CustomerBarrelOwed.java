package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 客户欠桶记录实体类，对应数据库 customer_owed_barrel 表。
 * <p>正数 owed_qty = 客户欠桶（少还了），负数 = 客户多还（可抵扣下次）</p>
 */
@Data
public class CustomerBarrelOwed {
    private Long id;
    private Long customerId;
    private Long stationId;
    /** 欠桶数：正数=客户欠桶，负数=客户多还 */
    private Integer owedQty;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
