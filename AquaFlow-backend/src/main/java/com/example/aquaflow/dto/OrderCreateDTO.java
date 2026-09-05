package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderCreateDTO {

    private Long customerId;
    private Long addressId;
    private Long stationId;
    private Integer source;
    private Integer paymentMethod;
    private String receiverName;
    private String receiverPhone;
    private String guardInfo;
    private String deliveryTimeRequest;
    private String specialNote;
    private Integer returnBucketQty;
    private BigDecimal extraDeposit;
    private List<OrderItemDTO> items;

    /** 库存不足时客户确认后重新提交设为 true (前端弹窗确认后再提交) */
    private Boolean confirmShortage;

    /** 幂等键：防止重复下单 */
    private String idempotencyKey;

    @Data
    public static class OrderItemDTO {
        private Long productId;
        private Integer quantity;
    }
}
