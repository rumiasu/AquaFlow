package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** 客户主动提交的不可改写清单。容量快照单价与真实历史押金余额分别表达。 */
public record HistoricalAssetClaimDTO(
    @NotNull @Positive Long stationId,
    @NotBlank @Size(max=64) String clientToken,
    @NotNull @AssertTrue Boolean confirmed,
    @NotNull @DecimalMin("0") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) BigDecimal depositBalance,
    @NotBlank @Size(max=500) String note,
    @NotNull @Size(max=100) List<@NotNull @Valid Item> items
) {
    // BigDecimal + intValueExact：不让 Jackson 将 1.5 个桶静默截成 1。
    public record Item(
        @NotNull @Positive Long productId,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") BigDecimal rights,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") BigDecimal held,
        @NotNull @DecimalMin("0") @DecimalMax("2147483647") BigDecimal tickets,
        @DecimalMin("0.01") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) BigDecimal unitPrice
    ) {}
    public record Review(@NotNull Boolean approve, @Size(max=500) String note) {}
    /** 认领选择用，含本站下架商品；不提供库存、成本或交易价格。 */
    public record ProductOption(Long id, String name, String spec, Integer category, String imageObjectName, String imageUrl) {}
}
