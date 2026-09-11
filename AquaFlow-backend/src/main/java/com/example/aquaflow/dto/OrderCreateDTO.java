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

    /**
     * 库存不足时是否继续下单（[AQ-055] 注释改为与后端实现一致）。
     * <p>false/缺省：只要存在缺货商品就返回 needConfirm（不创建订单），前端据此弹窗告知；
     * true：客户已确认接受缺货，后端跳过 needConfirm 直接创建订单（缺货部分后续补货配送）。</p>
     */
    private Boolean confirmShortage;

    /** 幂等键：防止重复下单 */
    private String idempotencyKey;

    @Data
    public static class OrderItemDTO {
        private Long productId;
        private Integer quantity;
    }
}
