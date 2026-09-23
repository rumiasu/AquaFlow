package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 站级「统一折扣」**平台预设档**的纯规则（v58）。
 *
 * <p>为什么给这几个算式单独写用例：预设档是"一键填入"的草稿，站长点一下就把张数与折扣填进表单、
 * 再点保存就**按各款水的价算出均价并快照进批次**。算错一位就是"界面写 8.55、批次存 8.54"这种
 * 展示与账目分叉（本仓把"展示与快照同源"当硬规则，见 {@code TicketPackageController.save}
 * 的 {@code unitPrice = price / qty}）。</p>
 */
@DisplayName("统一折扣 · 平台预设档（util/TicketPreset）")
class TicketPresetTest {

    private static final BigDecimal BASE_9 = new BigDecimal("9.00");
    private static final BigDecimal BASE_12 = new BigDecimal("12.00");

    @Test
    @DisplayName("预设档就是 10 / 20 / 100 张三档，折扣 9.5 / 9 / 8.5 折")
    void tiersAreTenTwentyHundred() {
        assertEquals(3, TicketPreset.tiers().size());
        assertEquals(10, TicketPreset.tiers().get(0).qty());
        assertEquals(950, TicketPreset.tiers().get(0).discountPerMille());
        assertEquals(20, TicketPreset.tiers().get(1).qty());
        assertEquals(900, TicketPreset.tiers().get(1).discountPerMille());
        assertEquals(100, TicketPreset.tiers().get(2).qty());
        assertEquals(850, TicketPreset.tiers().get(2).discountPerMille());
    }

    @Test
    @DisplayName("折扣文案由后端下发（950‰ → 9.5 折、900‰ → 9 折），前端不许自造映射表")
    void discountText() {
        assertEquals("9.5 折", TicketPreset.discountText(950));
        assertEquals("9 折", TicketPreset.discountText(900));
        assertEquals("8.5 折", TicketPreset.discountText(850));
        assertEquals("10 折", TicketPreset.discountText(1000));
    }

    @Test
    @DisplayName("总价 = 基准单价 × 张数 × 折扣；均价由**总价**除回来（与保存端同一算法）")
    void priceAndUnitPrice() {
        TicketPreset.Tier ten = TicketPreset.tiers().get(0);
        assertEquals(0, new BigDecimal("85.50").compareTo(TicketPreset.totalPrice(BASE_9, ten)),
                "9.00 × 10 × 0.95 = 85.50");
        assertEquals(0, new BigDecimal("8.55").compareTo(TicketPreset.unitPrice(BASE_9, ten)),
                "均价 = 85.50 ÷ 10 = 8.55（必须由总价除回来，不能拿 9.00 × 0.95 直接算）");

        TicketPreset.Tier hundred = TicketPreset.tiers().get(2);
        assertEquals(0, new BigDecimal("1020.00").compareTo(TicketPreset.totalPrice(BASE_12, hundred)),
                "12.00 × 100 × 0.85 = 1020.00");
        assertEquals(0, new BigDecimal("10.20").compareTo(TicketPreset.unitPrice(BASE_12, hundred)));

        assertEquals("10 张 9.5 折", TicketPreset.title(ten));
    }

    @Test
    @DisplayName("均价必须与「总价 ÷ 张数」逐字相等 —— 这就是保存端会快照进批次的那个数")
    void unitPriceMatchesSaveEndpointFormula() {
        for (BigDecimal base : new BigDecimal[]{new BigDecimal("9.00"), new BigDecimal("12.30"),
                new BigDecimal("22.00"), new BigDecimal("8.88")}) {
            for (TicketPreset.Tier t : TicketPreset.tiers()) {
                BigDecimal total = TicketPreset.totalPrice(base, t);
                BigDecimal expected = total.divide(BigDecimal.valueOf(t.qty()), 2, java.math.RoundingMode.HALF_UP);
                assertEquals(0, expected.compareTo(TicketPreset.unitPrice(base, t)),
                        "base=" + base + " qty=" + t.qty() + "：均价必须等于总价÷张数");
            }
        }
    }

    @Test
    @DisplayName("拿不到基准价就不给建议（返回 null），绝不凭空捏一个数")
    void noBasePriceNoSuggestion() {
        TicketPreset.Tier ten = TicketPreset.tiers().get(0);
        assertNull(TicketPreset.totalPrice(null, ten));
        assertNull(TicketPreset.totalPrice(BigDecimal.ZERO, ten));
        assertNull(TicketPreset.unitPrice(null, ten));
        assertNull(TicketPreset.title(null));
    }
}
