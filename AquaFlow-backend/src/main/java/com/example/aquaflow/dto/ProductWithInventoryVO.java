package com.example.aquaflow.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 + 库存联合视图 (管理端用).
 */
@Data
public class ProductWithInventoryVO {

    // ===== 商品基本信息 (product 表) =====
    private Long id;

    /** 归属水站：NULL=通用商品库（站长只读，只能选用）；非空=该站自定义商品（可完整编辑） */
    private Long ownerStationId;

    private String name;
    private Integer category;

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
    private String brand;
    private String spec;
    private String imageObjectName;
    private String description;
    private BigDecimal price;
    private BigDecimal deposit;
    private Integer maxPerOrder;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 当前水站库存配置 (inventory 表, 可能为 null = 未配置) =====
    private Long inventoryId;
    private Integer quantity;
    private Integer enabled;

    /** 本站售价（NULL/0 = 未覆盖，用 product.price 这个通用库参考价） */
    private BigDecimal salePrice;

    /** 本站押金（NULL/0 = 未覆盖，用 product.deposit） */
    private BigDecimal depositPrice;

    private Integer ticketEnabled;
    private BigDecimal ticketPrice;
    private Integer priorityDisplay;

    /** 本站是否已配置该商品（inventory 行是否存在）。前端用它区分"选用 / 已选用" */
    public Boolean getSelected() {
        return inventoryId != null;
    }

    /**
     * 本站生效售价（本站覆盖优先，否则通用库参考价）。
     * <p>与 {@code util/PriceUtil#calcUnitPrice} 同口径；前端展示必须用这个值，
     * 不要自己拿 price/salePrice 拼，否则会出现"列表价 ≠ 结算价"（本仓已有此类事故）。</p>
     */
    public BigDecimal getEffectivePrice() {
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0) {
            return salePrice;
        }
        return price != null ? price : BigDecimal.ZERO;
    }

    /** 本站生效押金（本站覆盖优先，否则通用库参考押金） */
    public BigDecimal getEffectiveDeposit() {
        if (depositPrice != null && depositPrice.compareTo(BigDecimal.ZERO) > 0) {
            return depositPrice;
        }
        return deposit != null ? deposit : BigDecimal.ZERO;
    }

    /** 图片临时访问 URL（由 Controller 注入） */
    private transient String imageUrl;
}