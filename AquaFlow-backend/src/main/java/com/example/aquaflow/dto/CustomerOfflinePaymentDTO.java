package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * PUT /api/customers/{id}/offline-payment 线下支付授权请求体。
 *
 * <p>对齐 miniapp {offlinePaymentEnabled:0|1}；原 Map 实现在缺字段时 NPE，
 * 这里用 @NotNull 把"授权标志必须存在"变成编译期可验证的契约。</p>
 *
 * <p>[v48] 站长在开通货到付款时同时定两件事（产品：「最好是给站长定，在设置是否允许货到付款时
 * 就给弹出来」「对首单和大额订单设限（特殊允许的客户可以大额）」）：</p>
 * <ul>
 *   <li>{@code singleLimit} —— 单笔上限；<b>传 {@code null} = 不限</b>（"特殊允许的客户可以大额"）。
 *       不传 ≠ 不改：本字段与 {@code allowFirstOrder} 都是**整份覆盖写**，
 *       所以"把上限改回不限"就是把 null 传上来（SQL 里刻意不加"非空才更新"的判断）。</li>
 *   <li>{@code allowFirstOrder} —— 是否允许该客户首单就用货到付款；<b>不传按 0（不放行）</b>。</li>
 * </ul>
 */
@Data
public class CustomerOfflinePaymentDTO {

    @NotNull(message = "offlinePaymentEnabled 不能为空（0 或 1）")
    private Integer offlinePaymentEnabled;

    /** 单笔上限；{@code null} = 不限。为负数在服务端直接拒绝。 */
    private java.math.BigDecimal singleLimit;

    /** 是否允许首单货到付款（1 = 允许，0/null = 不允许）。 */
    private Integer allowFirstOrder;
}
