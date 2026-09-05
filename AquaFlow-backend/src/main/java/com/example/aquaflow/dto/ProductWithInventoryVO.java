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
    private String name;
    private Integer category;
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
    private Integer ticketEnabled;
    private BigDecimal ticketPrice;
    private Integer priorityDisplay;

    /** 图片临时访问 URL（由 Controller 注入） */
    private transient String imageUrl;
}