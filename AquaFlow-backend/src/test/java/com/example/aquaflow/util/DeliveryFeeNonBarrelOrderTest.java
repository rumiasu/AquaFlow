package com.example.aquaflow.util;

import com.example.aquaflow.constant.DeliveryLimitMode;
import com.example.aquaflow.entity.StationDeliveryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本单没有桶装水时，只核验金额」的单元测试（2026-09-19 产品裁定）。
 *
 * <p>背景：起送量与免运费都是「桶数**或**金额」两条条件取或。桶数条件是为**循环桶**设的，
 * 而瓶装水 / 一次性桶 / 饮水机一件都不占桶 —— 本站若只配了桶数门槛，那类订单会命中
 * {@code 0 桶 < N 桶} 而被无理由拦下（或按 FEE 白加钱）。产品口径「**没用桶数时，核验金额即可**」
 * 落成两条规则里的「条件不适用」分支：</p>
 * <ul>
 *   <li>起送量：没桶 → 只看金额；金额也没配 → 该规则不生效（**不拦**）；</li>
 *   <li>免运费：没桶 → 只看金额；金额也没配 → 不免（**照收基础配送费**，优惠条件不适用就不给优惠）。</li>
 * </ul>
 *
 * <p>单独一个类而不是塞进 {@code DeliveryFeeUtilTest}：那一个类钉的是 v35 的原始计费口径，
 * 本类钉的是本次产品裁定新增的"条件不适用"语义，混在一起改的时候容易分不清哪条断言是旧契约。</p>
 */
class DeliveryFeeNonBarrelOrderTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 只配了桶数门槛的站：这是最容易踩坑的配置形态（站长以为"2 桶起送"已经生效）。 */
    private static StationDeliveryConfig barrelOnlyConfig() {
        StationDeliveryConfig c = StationDeliveryConfig.defaults(1L);
        c.setMinOrderBuckets(2);
        c.setMinOrderMode(DeliveryLimitMode.REJECT);
        c.setBaseDeliveryFee(bd("3.00"));
        c.setFreeDeliveryBuckets(2);
        return c;
    }

    @Test
    @DisplayName("起送量：纯非桶装单（0 桶）不再被桶数门槛拦下 —— 金额没配 = 该规则不生效")
    void pureNonBarrelOrderIsNotBlockedByBucketOnlyThreshold() {
        // 1 桶水的单：2 桶门槛 → 未达，照旧拦（保护原有契约）
        DeliveryFeeUtil.FeeResult barrelOrder = DeliveryFeeUtil.compute(barrelOnlyConfig(), 1, bd("20.00"),
                null, null, null);
        assertTrue(barrelOrder.isBlocked(), "有桶的单必须照旧按桶数门槛判：1 桶 < 2 桶 → 拦");

        // 0 桶（纯瓶装水/饮水机）：桶数条件不适用，金额又没配 → 不拦
        DeliveryFeeUtil.FeeResult nonBarrelOrder = DeliveryFeeUtil.compute(barrelOnlyConfig(), 0, bd("20.00"),
                null, null, null);
        assertFalse(nonBarrelOrder.isBlocked(),
                "纯非桶装单不该被桶数门槛拦下（桶数条件对它不适用），实际提示=" + nonBarrelOrder.getWarnings());
        // 只应剩基础配送费 3 元：起送量那条规则整体不生效 → 既不加收 minOrderFee、也不出提示
        assertEquals(0, nonBarrelOrder.getDeliveryFee().compareTo(bd("3.00")),
                "规则不生效 = 只有基础配送费，不该叠加起送量加收，实际=" + nonBarrelOrder.getDeliveryFee());
        assertTrue(nonBarrelOrder.getWarnings().stream().noneMatch(w -> w.contains("未达起送量")),
                "规则不生效就不该提示「未达起送量」，实际=" + nonBarrelOrder.getWarnings());
    }

    @Test
    @DisplayName("起送量：配了金额门槛就按金额核验（未达仍拦、达到放行）")
    void nonBarrelOrderIsJudgedByAmountWhenConfigured() {
        StationDeliveryConfig c = barrelOnlyConfig();
        c.setMinOrderAmount(bd("30.00"));

        assertTrue(DeliveryFeeUtil.compute(c, 0, bd("20.00"), null, null, null).isBlocked(),
                "纯非桶装单水费 20 < 30 → 未达起送量，拦");
        assertFalse(DeliveryFeeUtil.compute(c, 0, bd("30.00"), null, null, null).isBlocked(),
                "正好达到金额门槛 → 放行（达到即算达门槛）");
    }

    @Test
    @DisplayName("免运费：纯非桶装单只认金额；金额没配 → 不免（照收基础配送费）")
    void nonBarrelOrderDoesNotGetFreeDeliveryWithoutAmountRule() {
        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(barrelOnlyConfig(), 0, bd("99.00"), null, null, null);
        assertEquals(0, r.getDeliveryFee().compareTo(bd("3.00")),
                "免运费是「给优惠」：条件不适用时不给优惠（保守一侧），而不是默认免");
    }

    @Test
    @DisplayName("免运费：配了金额门槛则按金额免（达到即免）")
    void nonBarrelOrderGetsFreeDeliveryByAmount() {
        StationDeliveryConfig c = barrelOnlyConfig();
        c.setFreeDeliveryAmount(bd("40.00"));

        assertEquals(0, DeliveryFeeUtil.compute(c, 0, bd("39.00"), null, null, null)
                        .getDeliveryFee().compareTo(bd("3.00")),
                "水费 39 < 40 → 不免");
        assertEquals(0, DeliveryFeeUtil.compute(c, 0, bd("40.00"), null, null, null)
                        .getDeliveryFee().compareTo(BigDecimal.ZERO),
                "水费达到免运费金额 → 免");
    }

    @Test
    @DisplayName("有桶的单完全不受本次改动影响（桶数或金额，两条照旧取或）")
    void barrelOrderKeepsOriginalOrSemantics() {
        StationDeliveryConfig c = barrelOnlyConfig();
        c.setMinOrderAmount(bd("100.00"));

        // 2 桶达标（金额才 20，远不到 100）→ 不拦
        assertFalse(DeliveryFeeUtil.compute(c, 2, bd("20.00"), null, null, null).isBlocked(),
                "桶数达标即达门槛，与金额无关");
        // 1 桶且金额也不到 → 拦
        assertTrue(DeliveryFeeUtil.compute(c, 1, bd("20.00"), null, null, null).isBlocked(),
                "两条都不达 → 拦");
        // 1 桶但金额到了 → 不拦
        assertFalse(DeliveryFeeUtil.compute(c, 1, bd("120.00"), null, null, null).isBlocked(),
                "金额达标即达门槛，与桶数无关");
    }
}
