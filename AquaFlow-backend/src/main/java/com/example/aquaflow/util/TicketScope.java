package com.example.aquaflow.util;

import com.example.aquaflow.entity.Product;

/**
 * 「这一行该用哪张水票」—— 水票账户选择的**唯一判据**（2026-09-19 统一水票）。
 *
 * <p><b>产品口径</b>（原话）：「定制优先，**统一水票**是可以设置项，比如买 10 张都打 9.5 折、
 * 30 张统一 9 折这种，**定制和统一都有的情况下，定制优先，统一的仅在没有定制水票的桶时生效**」。</p>
 *
 * <p>落到判据上就是三条：</p>
 * <ol>
 *   <li><b>定制优先</b>：该商品在客户手里还有定制票余额（&gt; 0）→ 只能用它，
 *       <b>定制票不够也不拿统一票补差额</b>（"统一的仅在没有定制水票的桶时生效"的字面口径）；
 *       扣不够就整单失败（本仓没有混合支付，见 {@code docs/design/26} §26.6）。</li>
 *   <li><b>统一兜底</b>：该商品没有定制票余额（没有账户，或账户已用光）→ 若本站配了统一票档位
 *       且该商品**可被统一票抵扣**（只认桶装水，与押金/桶账口径一致）→ 用统一票账户（{@link #UNIFIED_PRODUCT_ID}）。</li>
 *   <li>两条都不满足 → 不能用票支付（返回 {@code null}，调用方给可读报错）。</li>
 * </ol>
 *
 * <p>⚠️ 本类只管**选哪个账户**，不管价格：统一票的折扣发生在**购买**那一刻
 * （档位总价 ÷ 张数 = 批次单价），抵扣时 1 张就是 1 张。</p>
 *
 * <p>⚠️ 账户用 {@code product_id = 0} 表达"站级通用票"：水票相关四张表的 {@code product_id}
 * **都没有外键**（实测 {@code information_schema.KEY_COLUMN_USAGE} 为空），所以零 schema 变更。</p>
 */
public final class TicketScope {

    /** 站级通用票（统一水票）的账户/档位标识：{@code product_id = 0}。 */
    public static final long UNIFIED_PRODUCT_ID = 0L;

    private TicketScope() {}

    /** 这个 id 是不是"站级通用票"。 */
    public static boolean isUnified(Long productId) {
        return productId != null && productId == UNIFIED_PRODUCT_ID;
    }

    /**
     * 该商品能否被**统一票**抵扣。
     *
     * <p>只认桶装水（{@code category=1}）。理由与押金/桶账一致：一次性桶、瓶装水、饮水器不占桶、
     * 没有"循环"这件事（见 {@code docs/design/03} §3.12）；统一票是"桶装水的折扣工具"。</p>
     */
    public static boolean unifiedEligible(Product product) {
        return BarrelScope.isBarrel(product);
    }

    /**
     * 选择扣票账户。
     *
     * @param productId          订单行上的商品 id（记录与幂等键仍用它）
     * @param customBalance      该客户在本站、该商品的**定制票**余额（没有账户时传 0）
     * @param unifiedConfigured  本站是否配了上架的统一票档位（{@code product_id=0}）
     * @param unifiedEligible    该商品是否可被统一票抵扣（见 {@link #unifiedEligible}）
     * @return 要扣的账户商品 id：定制票 → 该商品 id；统一票 → {@link #UNIFIED_PRODUCT_ID}；
     *         两条都不满足 → {@code null}
     */
    public static Long resolveAccount(Long productId, int customBalance,
                                      boolean unifiedConfigured, boolean unifiedEligible) {
        if (productId == null) {
            return null;
        }
        if (customBalance > 0) {
            // 定制优先：有余额就只用它（哪怕不够扣，也不拿统一票补 —— 这是裁定里那句"仅在没有定制水票的桶时生效"）
            return productId;
        }
        if (unifiedConfigured && unifiedEligible) {
            return UNIFIED_PRODUCT_ID;
        }
        return null;
    }
}
