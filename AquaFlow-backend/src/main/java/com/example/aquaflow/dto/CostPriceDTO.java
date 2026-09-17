package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 设置某商品在本站的进货成本价（v39），对应 {@code PUT /api/manager/gross-profit/cost}。
 *
 * <p>规格见 {@code docs/design/20} §2。</p>
 *
 * <p><b>为什么要有 {@link #clear} 这个字段，而不是"costPrice 传 null 就是清除"</b>：
 * 后者会把一个拼错的键变成一次静默的清空 —— 客户端发 {@code {productId:1, cost:9.9}}
 * （键名写错）时 {@code costPrice} 读到 null，服务端就会把成本价清掉，
 * 而站长看到的是"保存成功"。这与本仓 {@code DeliveryOrderActionDTO} 漏字段导致
 * "点已收款被当未收款"（AGENTS §8.15）是同一类事故：<b>静默 + 用户以为成功</b>。
 * 所以清除要显式声明，缺参数一律报错。</p>
 */
@Data
public class CostPriceDTO {

    private Long productId;

    /** 进货成本单价；{@link #clear} 为 false 时必填 */
    private BigDecimal costPrice;

    /** 显式清除成本价（变回"未填"）。为 true 时忽略 {@link #costPrice}。 */
    private Boolean clear;
}
