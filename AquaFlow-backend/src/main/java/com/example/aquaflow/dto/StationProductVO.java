package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 顾客侧「本站可售商品」视图。
 *
 * <p>为什么需要它（2026-09-16 商品与库存重构，docs/design/12-商品与库存重构.md §6.2）：
 * 重构后同一个商品在不同水站可以有**不同的售价/押金/水票价**，而原来
 * {@code /api/products/sale-by-station} 直接返回 {@code product} 实体 —— 只有平台参考价，
 * 客户端（商城 / 首页 / 详情 / 下单页 / 水票页）看到的价与结算价（{@code PriceUtil} 算的）
 * 就可能不一致，这正是本仓历史上"列表价 ≠ 结算价"的复发点。</p>
 *
 * <p>所以：<b>凡是给顾客看的价格，一律用本 VO 的 effective* 字段</b>，
 * 前端不要再拿 price / salePrice 自己拼（口径与 {@code PriceUtil} 完全一致：站级 &gt; 0 才覆盖）。</p>
 */
@Data
public class StationProductVO {

    // ===== 商品（通用库信息 or 本站自定义商品信息）=====
    private Long id;
    private String name;
    private Integer category;
    private String brand;
    private String spec;
    private String imageObjectName;
    private String description;
    /** 通用库参考价（自定义商品时就是本站自己的价） */
    private BigDecimal price;
    /** 通用库参考押金 */
    private BigDecimal deposit;
    private Integer maxPerOrder;

    // ===== 本站设置 =====
    private BigDecimal salePrice;
    private BigDecimal depositPrice;
    private Integer ticketEnabled;
    private BigDecimal ticketPrice;
    /** 优先展示（0/1）：顾客列表按它排在最前 */
    private Integer priorityDisplay;
    /** 本站可售库存（未配置本站时为 null） */
    private Integer availableQty;

    /** 图片临时访问 URL（由 Controller 注入） */
    private transient String imageUrl;

    /** 分类中文文案（后端唯一下发来源，前端不要自己写 1/2/3 映射表） */
    public String getCategoryText() {
        if (category == null) return "";
        switch (category) {
            case 1: return "桶装水";
            case 2: return "瓶装水";
            case 3: return "饮水器";
            default: return "";
        }
    }

    /** 本站生效售价：站级覆盖(&gt;0) 优先，否则通用库参考价。与 {@code PriceUtil#calcUnitPrice} 同口径 */
    public BigDecimal getEffectivePrice() {
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0) return salePrice;
        return price != null ? price : BigDecimal.ZERO;
    }

    /** 本站生效押金：站级覆盖(&gt;0) 优先，否则通用库参考押金。与 {@code PriceUtil#calcDeposit} 同口径 */
    public BigDecimal getEffectiveDeposit() {
        if (depositPrice != null && depositPrice.compareTo(BigDecimal.ZERO) > 0) return depositPrice;
        return deposit != null ? deposit : BigDecimal.ZERO;
    }

    /**
     * 本站生效水票价（"一张水票抵多少钱"）：本站水票价 → 通用库水票价 → 本站售价 → 参考价。
     * 与 {@code PriceUtil#calcUnitPrice(product, inv, PayMethod.TICKET)} 同口径 —— 少一级就会出现
     * "买票按 A 价、用票按 B 价"（[AQ-031] 就是这条链上的历史事故）。
     */
    public BigDecimal getEffectiveTicketPrice() {
        if (ticketPrice != null && ticketPrice.compareTo(BigDecimal.ZERO) > 0) return ticketPrice;
        return getEffectivePrice();
    }

    /** 是否有现货（0 库存仍可下单 —— 产品口径：提示"暂时没货，需要等待配送"） */
    public Boolean getInStock() {
        return availableQty != null && availableQty > 0;
    }
}
