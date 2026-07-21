package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退桶请求DTO
 */
@Data
public class BarrelReturnDTO {

    /** 客户ID */
    private Integer customerId;

    /** 退桶数量 */
    private Integer quantity;

    /** 退押金金额（可选） */
    private BigDecimal depositRefund;

    /** 备注 */
    private String note;
}
