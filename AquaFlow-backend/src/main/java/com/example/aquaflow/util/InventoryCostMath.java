package com.example.aquaflow.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 可空库存成本池；未知不会变成零，分摊余分留在库存，最后出库取尽余值。 */
public final class InventoryCostMath {
    private InventoryCostMath() {}
    public record Result(BigDecimal remaining, BigDecimal movement) {}
    public static BigDecimal money(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 2 || value.precision()-value.scale() > 16)
            throw new IllegalArgumentException("进货总成本须为非负金额，最多两位小数");
        return value.setScale(2);
    }
    public static Result move(int before, BigDecimal value, int delta, BigDecimal incoming) {
        if (delta > 0) {
            BigDecimal next = incoming == null || before > 0 && value == null ? null
                    : (before > 0 ? value : BigDecimal.ZERO).add(incoming);
            return new Result(next, incoming);
        }
        int take = -delta;
        if (before < take || before <= 0 || value == null) return new Result(before == take ? BigDecimal.ZERO : null, null);
        BigDecimal amount = take == before ? value : value.multiply(BigDecimal.valueOf(take)).divide(BigDecimal.valueOf(before), 2, RoundingMode.HALF_UP);
        return new Result(value.subtract(amount), amount);
    }
}
