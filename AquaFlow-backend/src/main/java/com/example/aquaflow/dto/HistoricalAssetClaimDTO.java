package com.example.aquaflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** 客户主动提交的不可改写清单。桶单价与真实历史押金金额分别表达。 */
public record HistoricalAssetClaimDTO(
    @NotNull(message="水站不能为空") @Positive(message="请选择有效的水站") Long stationId,
    @NotBlank(message="提交编号无效，请刷新后重试") @Size(max=64, message="提交编号无效，请刷新后重试") String clientToken,
    @NotNull(message="请确认认领清单") @AssertTrue(message="请确认认领清单") Boolean confirmed,
    @NotNull(message="历史押金余额不能为空") @DecimalMin(value="0", message="历史押金余额不能小于0") @DecimalMax(value="99999999.99", message="历史押金余额不能超过99999999.99") @Digits(integer=8,fraction=2, message="历史押金余额格式不正确，请核对金额位数") BigDecimal depositBalance,
    @NotBlank(message="说明不能为空") @Size(max=500, message="说明最多500字") String note,
    @NotNull(message="认领明细不能为空") @Size(max=100, message="认领明细最多100项") List<@NotNull(message="认领明细不能为空") @Valid Item> items
) {
    // BigDecimal + intValueExact：不让 Jackson 将 1.5 个桶静默截成 1。
    public record Item(
        @NotNull(message="商品不能为空") @Positive(message="请选择有效的商品") Long productId,
        @NotNull(message="拥有桶数不能为空") @DecimalMin(value="0", message="拥有桶数不能小于0") @DecimalMax(value="2147483647", message="拥有桶数不能超过2147483647") BigDecimal rights,
        // 仅兼容旧请求摘要，不参与入账；缺失时不伪造实物数量。
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @DecimalMin(value="0", message="历史在手桶数不能小于0") @DecimalMax(value="2147483647", message="历史在手桶数不能超过2147483647") BigDecimal held,
        @NotNull(message="水票张数不能为空") @DecimalMin(value="0", message="水票张数不能小于0") @DecimalMax(value="2147483647", message="水票张数不能超过2147483647") BigDecimal tickets,
        @DecimalMin(value="0.01", message="单价不能小于0.01") @DecimalMax(value="99999999.99", message="单价不能超过99999999.99") @Digits(integer=8,fraction=2, message="单价格式不正确，请核对金额位数") BigDecimal unitPrice
    ) {}
    public record Review(
        @NotNull(message="审核结果不能为空") Boolean approve,
        @Size(max=500, message="说明最多500字") String note
    ) {}
    /** 认领选择用，含本站下架商品；不提供库存、成本或交易价格。 */
    public record ProductOption(Long id, String name, String spec, Integer category, String imageObjectName, String imageUrl) {}
}
