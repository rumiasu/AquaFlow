package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** POST /api/payments/quote 请求体，对齐 miniapp 实际发送 {items, paymentMethod, stationId} */
@Data
public class PaymentQuoteDTO {

    @NotNull(message = "请先选择服务水站")
    private Long stationId;

    @NotNull(message = "支付方式不能为空")
    @Min(value = 1, message = "支付方式非法")
    private Integer paymentMethod;

    @NotNull(message = "商品明细不能为空")
    @Size(min = 1, message = "至少包含一个商品")
    @Valid
    private List<PaymentQuoteItemDTO> items;

    /**
     * 收货地址ID（2026-09-17 新增，v35）。
     *
     * <p><b>为什么必须传</b>：配送范围与楼层费要靠它取地址的坐标与楼层，而下单侧
     * （{@code OrderCreateDTO.addressId}）本来就有这个值。若报价不传，两侧算出的费用就可能不同
     * —— 那正是本仓"计价双轨"事故的形状（结算页一个价、下单另一个价）。</p>
     *
     * <p>允许为 {@code null}：此时距离按"算不出来"处理（不收远程费、不拦单），
     * 并在返回的 {@code warnings} 里说明。存量调用方不传不会报错。</p>
     */
    private Long addressId;
}
