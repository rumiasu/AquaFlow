package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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

    /**
     * 订单来源：1 电话 / 2 微信 / 3 小程序（取值正本是 {@code sql/schema.sql} 里
     * {@code orders.source} 的列注释）。
     *
     * <p>⚠️ [2026-09-25 架构评审问题 7] 原为无约束的裸 {@code Integer}，客户端传任意值都落库。
     * 它只是来源标记（没有业务分支读它），但"枚举入参必须白名单校验"是本仓已立的规则
     * （AGENTS §6）—— 顺带一提：员工代客下单页显式传 1（电话），客户端小程序传 3。</p>
     */
    @Min(value = 1, message = "订单来源非法")
    @Max(value = 3, message = "订单来源非法")
    private Integer source;

    /**
     * 支付方式：1 微信 / 2 现金(货到付款) / 3 水票 —— <b>白名单正本在 {@code constant/PayMethod}</b>。
     *
     * <p>⚠️ [2026-09-25 架构评审问题 7] 原先只判"非空"，实测传 99 也能建单成功：它既不走现金
     * 分支（于是绕开"现金需水站开通"的校验），也不走水票分支，付款状态停在待收款，而站长/
     * 配送员列表的可见性判据是"已付款 或 现金" ⇒ 这单**谁也看不见**，却照样扣了库存、
     * 建了配送中桶。这里的注解是第一道，应用服务边界还有一次 {@code PayMethod.isValid} 校验
     * （注解会被新的调用路径绕过，白名单不会）。</p>
     */
    @Min(value = 1, message = "不支持的支付方式")
    @Max(value = 3, message = "不支持的支付方式")
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
