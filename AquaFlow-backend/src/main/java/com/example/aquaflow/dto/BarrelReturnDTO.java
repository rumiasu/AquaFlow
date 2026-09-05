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

    /** 产品ID（退桶归属产品，用于按型核算持有桶数） */
    private Integer productId;

    /** 退桶数量 */
    private Integer quantity;

    /** 退押金金额（可选） */
    private BigDecimal depositRefund;

    /** 备注 */
    private String note;
}
