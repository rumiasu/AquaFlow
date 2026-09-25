package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建站长资产调整单的请求体。
 *
 * <p>{@code qty} / {@code amount} 一律传<b>正数</b>，方向由 {@code adjustType} 决定；
 * 唯一例外是 {@code OVER_ADJUST}（订正欠桶），其 {@code qty} 为带符号增量：
 * 正=补记欠桶，负=核销欠桶。</p>
 */
@Data
public class AdjustmentCreateDTO {

    @NotNull(message = "客户不能为空")
    private Long customerId;

    @NotBlank(message = "调整类型不能为空")
    @Size(max = 32, message = "调整类型过长")
    private String adjustType;

    /** 桶类/水票类必填；押金类可不填 */
    private Long productId;

    private Integer qty;

    private BigDecimal amount;

    /** 补录单价（桶权益用）。不传则回退商品当前押金，并标记为「推断值，退款需二次确认」 */
    private BigDecimal unitPrice;

    @NotBlank(message = "调整原因必填")
    @Size(max = 200, message = "调整原因不能超过 200 字")
    private String reason;

    /** 证据图 objectName，逗号分隔 */
    @Size(max = 500, message = "证据图字段过长")
    private String evidence;

    /** 客户端幂等键：同一 token 重复提交返回原单 */
    @NotBlank(message = "clientToken 不能为空（幂等键）")
    @Size(max = 64, message = "clientToken 过长")
    private String clientToken;
}
