package com.example.aquaflow.util;

import com.example.aquaflow.constant.DeliveryLimitMode;
import com.example.aquaflow.constant.FloorFeeMode;
import com.example.aquaflow.entity.StationDeliveryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送计费规则的单元测试（v35）。
 *
 * <p>计费规则是纯函数，所以用单元测试而不是集成用例 —— 跑得快，而且能把"各种配置组合"穷举，
 * 不必为每条规则造一遍水站/商品/订单。**端到端那部分（报价与下单同口径）在
 * {@code DeliveryFeeIntegrationTest} 里用真 HTTP 验**，两者分工明确。</p>
 */
class DeliveryFeeUtilTest {

    /** 全 0 配置 = 没配过。存量水站都是这个状态，行为必须与升级前一致。 */
    @Test
    @DisplayName("默认配置：所有费用为 0、不拦单、不出提示")
    void defaultConfigChargesNothing() {
        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(
                StationDeliveryConfig.defaults(1L), 2, bd("40.00"), null, null, null);
        assertEquals(0, r.getDeliveryFee().compareTo(BigDecimal.ZERO), "默认不收配送费");
        assertEquals(0, r.getFloorFee().compareTo(BigDecimal.ZERO), "默认不收楼层费");
        assertFalse(r.isBlocked(), "默认不拦单");
        assertTrue(r.getWarnings().isEmpty(), "默认没有提示，实际=" + r.getWarnings());
    }

    /** 配置传 null 也要走同一套默认值 —— 业务侧不该因为"没查出行"就换一条路径。 */
    @Test
    @DisplayName("配置为 null 等同默认配置（少一个分支就少一处口径分叉）")
    void nullConfigBehavesLikeDefaults() {
        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(null, 1, bd("20.00"), null, 6, 0);
        assertEquals(0, r.getFeeTotal().compareTo(BigDecimal.ZERO));
        assertFalse(r.isBlocked());
    }

    // ==================== 起送量 ====================

    @Test
    @DisplayName("起送量 WARN：放行 + 提示，不收费")
    void minOrderWarn() {
        StationDeliveryConfig c = cfg();
        c.setMinOrderBuckets(2);
        c.setMinOrderMode(DeliveryLimitMode.WARN);

        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 1, bd("20.00"), null, null, null);
        assertFalse(r.isBlocked(), "WARN 不得拦单");
        assertEquals(0, r.getDeliveryFee().compareTo(BigDecimal.ZERO));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("未达起送量")), "应有提示: " + r.getWarnings());
    }

    @Test
    @DisplayName("起送量 REJECT：拦单并给出可读原因")
    void minOrderReject() {
        StationDeliveryConfig c = cfg();
        c.setMinOrderBuckets(2);
        c.setMinOrderMode(DeliveryLimitMode.REJECT);

        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 1, bd("20.00"), null, null, null);
        assertTrue(r.isBlocked(), "REJECT 必须拦单");
        assertTrue(r.getBlockReason() != null && r.getBlockReason().contains("未达起送量"),
                "原因应可读，实际=" + r.getBlockReason());
    }

    @Test
    @DisplayName("起送量 FEE：放行但加收费用")
    void minOrderFee() {
        StationDeliveryConfig c = cfg();
        c.setMinOrderBuckets(2);
        c.setMinOrderMode(DeliveryLimitMode.FEE);
        c.setMinOrderFee(bd("5.00"));

        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 1, bd("20.00"), null, null, null);
        assertFalse(r.isBlocked());
        assertEquals(0, r.getDeliveryFee().compareTo(bd("5.00")), "应加收 5 元，实际=" + r.getDeliveryFee());
    }

    @Test
    @DisplayName("起送量：桶数与金额取或 —— 满足其一即达门槛")
    void minOrderBucketsOrAmount() {
        StationDeliveryConfig c = cfg();
        c.setMinOrderBuckets(5);
        c.setMinOrderAmount(bd("100.00"));
        c.setMinOrderMode(DeliveryLimitMode.REJECT);

        // 桶数不够但金额够 → 放行
        assertFalse(DeliveryFeeUtil.compute(c, 1, bd("120.00"), null, null, null).isBlocked(),
                "金额达标就该放行（门槛是「或」不是「与」）");
        // 金额不够但桶数够 → 放行
        assertFalse(DeliveryFeeUtil.compute(c, 5, bd("10.00"), null, null, null).isBlocked(),
                "桶数达标就该放行");
        // 两个都不够 → 拦
        assertTrue(DeliveryFeeUtil.compute(c, 4, bd("99.00"), null, null, null).isBlocked(),
                "两个门槛都没达才拦");
    }

    // ==================== 配送范围 ====================

    @Test
    @DisplayName("配送范围：距离算不出来时不判、不拦（把「没数据」当「超范围」会挡掉所有客户）")
    void unknownDistanceNeverBlocks() {
        StationDeliveryConfig c = cfg();
        c.setDeliveryRadiusM(3000);
        c.setOverRadiusMode(DeliveryLimitMode.REJECT);

        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 1, bd("20.00"), null, null, null);
        assertFalse(r.isBlocked(), "距离未知绝不能拦单");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("无法判断配送距离")),
                "但要提示: " + r.getWarnings());
    }

    @Test
    @DisplayName("配送范围 FEE：超范围加收远程费；范围内不加")
    void overRadiusFee() {
        StationDeliveryConfig c = cfg();
        c.setDeliveryRadiusM(3000);
        c.setOverRadiusMode(DeliveryLimitMode.FEE);
        c.setRemoteFee(bd("8.00"));

        assertEquals(0, DeliveryFeeUtil.compute(c, 1, bd("20.00"), 2500.0, null, null)
                .getDeliveryFee().compareTo(BigDecimal.ZERO), "范围内不收远程费");
        assertEquals(0, DeliveryFeeUtil.compute(c, 1, bd("20.00"), 5000.0, null, null)
                .getDeliveryFee().compareTo(bd("8.00")), "超范围收 8 元");
    }

    @Test
    @DisplayName("配送范围：半径没配（null）时不做任何范围判断")
    void noRadiusNoCheck() {
        StationDeliveryConfig c = cfg();
        c.setOverRadiusMode(DeliveryLimitMode.REJECT);
        // deliveryRadiusM 保持 null
        assertFalse(DeliveryFeeUtil.compute(c, 1, bd("20.00"), 999999.0, null, null).isBlocked(),
                "没配半径就不该有任何范围拦截");
    }

    // ==================== 运费 ====================

    @Test
    @DisplayName("基础配送费：达到免运费门槛（桶数或金额）即免")
    void freeDeliveryThreshold() {
        StationDeliveryConfig c = cfg();
        c.setBaseDeliveryFee(bd("3.00"));
        c.setFreeDeliveryBuckets(2);

        assertEquals(0, DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, null, null)
                .getDeliveryFee().compareTo(BigDecimal.ZERO), "2 桶达门槛应免运费");
        assertEquals(0, DeliveryFeeUtil.compute(c, 1, bd("40.00"), null, null, null)
                .getDeliveryFee().compareTo(bd("3.00")), "1 桶未达门槛应收 3 元");
    }

    @Test
    @DisplayName("基础配送费：没配免运费门槛 → 一直收（不是一直免）")
    void noFreeThresholdAlwaysCharges() {
        StationDeliveryConfig c = cfg();
        c.setBaseDeliveryFee(bd("3.00"));
        assertEquals(0, DeliveryFeeUtil.compute(c, 99, bd("9999.00"), null, null, null)
                .getDeliveryFee().compareTo(bd("3.00")), "没有免运费门槛就该照收");
    }

    // ==================== 楼层费 ====================

    @Test
    @DisplayName("楼层费：有电梯不收、免费楼层以内不收、无电梯超层才收")
    void floorFeeRules() {
        StationDeliveryConfig c = cfg();
        c.setFloorFeePerLevel(bd("2.00"));
        c.setFloorFreeLevel(1);

        assertEquals(0, DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, 6, 1)
                .getFloorFee().compareTo(BigDecimal.ZERO), "有电梯不收");
        assertEquals(0, DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, 1, 0)
                .getFloorFee().compareTo(BigDecimal.ZERO), "1 层在免费楼层内");
        // 6 层无电梯、免费 1 层 → 超 5 层 × 2 元 = 10 元（按单）
        assertEquals(0, DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, 6, 0)
                .getFloorFee().compareTo(bd("10.00")), "6 层无电梯按单收 10 元");
    }

    @Test
    @DisplayName("楼层费：按桶时乘桶数（桶数为 0 也算 1 份 —— 上楼这件事本身要出力）")
    void floorFeePerBucket() {
        StationDeliveryConfig c = cfg();
        c.setFloorFeePerLevel(bd("2.00"));
        c.setFloorFreeLevel(1);
        c.setFloorFeeMode(FloorFeeMode.PER_BUCKET);

        assertEquals(0, DeliveryFeeUtil.compute(c, 3, bd("60.00"), null, 3, 0)
                .getFloorFee().compareTo(bd("12.00")), "3 桶 × 超 2 层 × 2 元 = 12 元");
        assertEquals(0, DeliveryFeeUtil.compute(c, 0, bd("0.00"), null, 3, 0)
                .getFloorFee().compareTo(bd("4.00")), "桶数 0 也算 1 份 → 超 2 层 × 2 元 = 4 元");
    }

    @Test
    @DisplayName("楼层费：楼层未填或电梯未确认 → 不收、只提示（拿不准就不收，宁可少收不可乱收）")
    void floorFeeUnknownNeverCharges() {
        StationDeliveryConfig c = cfg();
        c.setFloorFeePerLevel(bd("2.00"));

        DeliveryFeeUtil.FeeResult noFloor = DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, null, 0);
        assertEquals(0, noFloor.getFloorFee().compareTo(BigDecimal.ZERO), "楼层没填不得收");
        assertTrue(noFloor.getWarnings().stream().anyMatch(w -> w.contains("楼层")),
                "应提示站长/客户补填: " + noFloor.getWarnings());

        DeliveryFeeUtil.FeeResult noElevator = DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, 6, null);
        assertEquals(0, noElevator.getFloorFee().compareTo(BigDecimal.ZERO),
                "电梯未确认（NULL）不得当成无电梯收费 —— 那会向客户乱收钱");
    }

    @Test
    @DisplayName("楼层费：本站没配单价（0）时，即使楼层很高也不收、也不提示")
    void floorFeeNotConfiguredIsSilent() {
        StationDeliveryConfig c = cfg();   // floorFeePerLevel 为 0
        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 2, bd("40.00"), null, 20, 0);
        assertEquals(0, r.getFloorFee().compareTo(BigDecimal.ZERO));
        assertTrue(r.getWarnings().isEmpty(), "没配楼层费就不该打扰客户，实际=" + r.getWarnings());
    }

    // ==================== 组合 ====================

    @Test
    @DisplayName("组合：未达起送量加收 + 超范围远程费 + 无电梯楼层费，三项可以叠加")
    void feesAccumulate() {
        StationDeliveryConfig c = cfg();
        c.setMinOrderBuckets(2);
        c.setMinOrderMode(DeliveryLimitMode.FEE);
        c.setMinOrderFee(bd("5.00"));
        c.setDeliveryRadiusM(3000);
        c.setOverRadiusMode(DeliveryLimitMode.FEE);
        c.setRemoteFee(bd("8.00"));
        c.setFloorFeePerLevel(bd("2.00"));

        DeliveryFeeUtil.FeeResult r = DeliveryFeeUtil.compute(c, 1, bd("20.00"), 5000.0, 4, 0);
        assertEquals(0, r.getDeliveryFee().compareTo(bd("13.00")), "配送费 = 5(起送) + 8(远程)");
        assertEquals(0, r.getFloorFee().compareTo(bd("6.00")), "楼层费 = 超 3 层 × 2 元");
        assertEquals(0, r.getFeeTotal().compareTo(bd("19.00")));
        assertEquals(3, r.getWarnings().size(), "三条提示各一条: " + r.getWarnings());
    }

    private static StationDeliveryConfig cfg() {
        return StationDeliveryConfig.defaults(1L);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
