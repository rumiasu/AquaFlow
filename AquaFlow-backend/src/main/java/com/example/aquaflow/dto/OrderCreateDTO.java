package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderCreateDTO {

    private Long customerId;

    /**
     * 收货地址ID（v35 起被配送计费依赖）。
     *
     * <p>⚠️ 前端传来的 JSON 数字可能是 {@code Integer}，而这里必须是 {@code Long} ——
     * 与 {@link PaymentQuoteDTO#getAddressId()} 保持同一类型，否则报价与下单两侧
     * 算出的楼层费/远程费可能不同（"计价双轨"事故的形状）。
     * Jackson 会把 JSON 数字直接转成目标类型，客户端无需改动。</p>
     */
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

    /** 订单至少含一个商品；缺失/空列表在边界被 Bean Validation 拒回，不进入下单流程 */
    @NotNull(message = "订单商品不能为空")
    @NotEmpty(message = "订单商品不能为空")
    @Valid
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
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少 1")
        private Integer quantity;
    }
}
