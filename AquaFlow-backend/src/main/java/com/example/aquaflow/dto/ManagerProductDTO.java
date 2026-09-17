package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端商品保存/更新请求体（Phase F-3：ManagerProductController save/update 由 Map 强类型化）。
 * <p>站长端以 JSON 数字发送 price/deposit/quantity 等（parseFloat/parseInt 后传输），
 * 类型化字段安全；imageUrl 为旧格式（后端解析出 imageObjectName），保留兼容。</p>
 */
public class ManagerProductDTO {

    /** POST /api/manager/products：创建商品（+可选库存配置块） */
    @Data
    public static class Save {
        @NotBlank(message = "商品名称不能为空")
        private String name;

        private Integer category;

        private String brand;

        private String spec;

        private String imageObjectName;

        /** 旧格式：前端传完整 URL，后端解析 objectName */
        private String imageUrl;

        private String description;

        @NotNull(message = "价格不能为空")
        private BigDecimal price;

        private BigDecimal deposit;

        private Integer maxPerOrder;

        private Integer sort;

        // ---- 可选库存/上架/水票配置块（任一非空即 upsertSettings）----

        private Integer quantity;

        private Integer enabled;

        /**
         * 本站售价（覆盖 product.price 这个通用库参考价）。
         * <p>2026-09-16 修复：此前前端「本站售价(选填)」一直在发，但本 DTO 没有这个字段 →
         * Jackson 静默忽略、`inventory` 也没有对应列，站长填的值等于没填（见 AGENTS.md §8.15 同类事故）。
         * 留空或 0 = 用通用库参考价。</p>
         */
        private BigDecimal salePrice;

        /** 本站押金（覆盖 product.deposit）。留空或 0 = 用通用库参考押金。 */
        private BigDecimal depositPrice;

        private Integer ticketEnabled;

        private BigDecimal ticketPrice;

        private Integer priorityDisplay;
    }

    /** PUT /api/manager/products/{id}：更新商品（全字段可选，patch 语义） */
    @Data
    public static class Update {
        private String name;

        private Integer category;

        private String brand;

        private String spec;

        private String imageObjectName;

        private String imageUrl;

        private String description;

        private BigDecimal price;

        private BigDecimal deposit;

        private Integer maxPerOrder;

        private Integer status;

        private Integer sort;

        private Integer quantity;

        private Integer enabled;

        /** 本站售价（覆盖通用库参考价 product.price）；留空或 0 = 用参考价。详见 {@link Save#getSalePrice()} */
        private BigDecimal salePrice;

        /** 本站押金（覆盖通用库参考押金 product.deposit）；留空或 0 = 用参考押金。 */
        private BigDecimal depositPrice;

        private Integer ticketEnabled;

        private BigDecimal ticketPrice;

        private Integer priorityDisplay;
    }
}
