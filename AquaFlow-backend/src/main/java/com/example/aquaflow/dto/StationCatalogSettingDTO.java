package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 站长对**本站**某商品的设置（选品/上架/定价），请求体只含"站级"字段。
 *
 * <p>规格见 {@code docs/design/12-商品与库存重构.md} §6.1。要点：</p>
 * <ul>
 *   <li>通用库商品的名称/规格/图片/描述由开发者维护，站长不可改 —— 所以本 DTO 里**没有**这些字段；</li>
 *   <li><b>库存不在这里</b>（走 {@code POST /api/manager/catalog/{id}/stock}），否则会绕过
 *       {@code inventory_record} 流水；</li>
 *   <li>价格三态：{@code null} = 保持原值；{@code 0} = 清除覆盖（回落通用库参考价）；
 *       {@code >0} = 设为该值。</li>
 * </ul>
 */
@Data
public class StationCatalogSettingDTO {

    /** 本站是否上架（0 否 1 是）。选用时不传 = 默认 0（未上架）——"选了就自动卖"是对账事故的高发点 */
    private Integer enabled;

    /** 本站售价（覆盖通用库参考价 product.price） */
    private BigDecimal salePrice;

    /** 本站押金（覆盖通用库参考押金 product.deposit） */
    private BigDecimal depositPrice;

    /** 本站是否支持水票 */
    private Integer ticketEnabled;

    /** 本站水票价 */
    private BigDecimal ticketPrice;

    /** 优先展示（上限 3，由服务侧校验） */
    private Integer priorityDisplay;
}
