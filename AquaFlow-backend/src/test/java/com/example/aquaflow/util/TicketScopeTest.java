package com.example.aquaflow.util;

import com.example.aquaflow.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一水票「选哪个账户」的判据（v54，纯规则）。规格见 {@code docs/design/26}。
 *
 * <p>产品裁定原文：「定制优先，**统一水票**是可以设置项…定制和统一都有的情况下，定制优先，
 * **统一的仅在没有定制水票的桶时生效**」。</p>
 *
 * <p>为什么给这几条 if 单独写用例：它们是"钱往哪个账户走"的唯一判据，
 * 被集成用例覆盖时只能验到"某一组数据下结果对"，而被随手的
 * {@code if (customBalance >= qty)} 之类的"顺手优化"改坏时（那会变成**混合支付**），
 * 只有边界表能挡住。</p>
 */
@DisplayName("统一水票 · 账户选择判据（util/TicketScope）")
class TicketScopeTest {

    private static Product product(long id, int category) {
        Product p = new Product();
        p.setId(id);
        p.setCategory(category);
        return p;
    }

    @Test
    @DisplayName("定制优先：只要有定制票余额，就只用定制（余额够不够都不换统一票）")
    void customBalanceAlwaysWins() {
        // 有余额 → 返回该商品账户，哪怕统一票已配置、哪怕余额只有 1 张而本单要 10 张
        assertEquals(Long.valueOf(7L), TicketScope.resolveAccount(7L, 1, true, true),
                "定制票有余额时必须只用定制（不够也不拿统一票补差额）");
        assertEquals(Long.valueOf(7L), TicketScope.resolveAccount(7L, 99, true, true));
        // 统一票没配置也照样是定制
        assertEquals(Long.valueOf(7L), TicketScope.resolveAccount(7L, 1, false, true));
    }

    @Test
    @DisplayName("统一兜底：定制余额为 0 且本站配了统一票、商品是桶装水 → 站级通用票(0)")
    void unifiedTakesOverWhenNoCustom() {
        assertEquals(Long.valueOf(TicketScope.UNIFIED_PRODUCT_ID),
                TicketScope.resolveAccount(7L, 0, true, true),
                "没有定制票余额时应落到统一票账户");
        // 余额为负（脏数据）同样按"没有定制票"处理，不能因为负数就卡住客户
        assertEquals(Long.valueOf(TicketScope.UNIFIED_PRODUCT_ID),
                TicketScope.resolveAccount(7L, -3, true, true));
    }

    @Test
    @DisplayName("统一票只抵桶装水：瓶装水/饮水器没有定制票时直接用不了票（不给统一票）")
    void unifiedOnlyForBarrelCategory() {
        assertNull(TicketScope.resolveAccount(7L, 0, true, false),
                "非桶装商品不得使用统一水票");
        assertTrue(TicketScope.unifiedEligible(product(7L, BarrelScope.CATEGORY_BARREL)));
        assertFalse(TicketScope.unifiedEligible(product(7L, 2)), "瓶装水不是桶");
        assertFalse(TicketScope.unifiedEligible(product(7L, 3)), "饮水器不是桶");
        assertFalse(TicketScope.unifiedEligible(null), "拿不准就不认（同 BarrelScope 口径）");
    }

    @Test
    @DisplayName("两条都不满足 → null（调用方给可读报错，不许默默退回别的账户）")
    void neitherAvailableReturnsNull() {
        assertNull(TicketScope.resolveAccount(7L, 0, false, true), "没配统一票 + 没定制票 = 不能用票");
        assertNull(TicketScope.resolveAccount(null, 0, true, true), "商品 id 都没有就谈不上账户");
    }

    @Test
    @DisplayName("product_id=0 是站级通用票的标识，不能与真实商品混淆")
    void unifiedProductIdIsZero() {
        assertEquals(0L, TicketScope.UNIFIED_PRODUCT_ID);
        assertTrue(TicketScope.isUnified(0L));
        assertFalse(TicketScope.isUnified(1L));
        assertFalse(TicketScope.isUnified(null));
    }
}
