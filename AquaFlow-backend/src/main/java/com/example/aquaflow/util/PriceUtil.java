package com.example.aquaflow.util;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;

import java.math.BigDecimal;

/**
 * 计价工具：全系统唯一的单价计算入口。
 * <p>
 * 背景一：此前"结算页报价"和"下单落单"各算各的——
 * quote() 在水票支付时用 product.ticketPrice，createOrder() 却恒用 product.price，
 * 导致客户在结算页看到的价格与最终订单金额不一致（计价双轨，真实业务里直接引发客诉）。
 * </p>
 * <p>
 * 背景二（[AQ-031]）：水票价真正的来源是 {@code inventory.ticket_price}（站长在管理端按站配置，
 * 见 ManagerProductController → inventoryMapper.upsertSettings）。历史实现只读
 * {@code product.ticket_price}（product 表列，实际恒为 0），导致站长配的水票价从不生效、
 * 水票支付恒按零售价结算。故水票价必须以站级库存配置为准，product 表列仅作兜底。
 * </p>
 * 所有涉及单价的地方必须调用本方法，禁止各自写 if。
 */
public final class PriceUtil {

    private PriceUtil() {}

    /**
     * 兼容旧调用：无站级库存信息时按 product 表列计算（不建议新代码使用）。
     */
    public static BigDecimal calcUnitPrice(Product product, Integer paymentMethod) {
        return calcUnitPrice(product, null, paymentMethod);
    }

    /**
     * 按支付方式计算商品单价：水票支付时优先取站级库存水票价（inventory.ticket_price），
     * 无有效站级配置时回退 product.ticket_price；否则用零售价。
     *
     * @param inventory 该站该商品的库存配置（含水票价），可为 null
     */
    public static BigDecimal calcUnitPrice(Product product, Inventory inventory, Integer paymentMethod) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (Integer.valueOf(PayMethod.TICKET).equals(paymentMethod)) {
            BigDecimal stTicketPrice = inventory != null ? inventory.getTicketPrice() : null;
            if (stTicketPrice != null && stTicketPrice.compareTo(BigDecimal.ZERO) > 0) {
                return stTicketPrice;
            }
            if (product.getTicketPrice() != null && product.getTicketPrice().compareTo(BigDecimal.ZERO) > 0) {
                return product.getTicketPrice();
            }
        }
        return product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
    }

    /** 数量 × 单价，空值安全 */
    public static BigDecimal calcItemAmount(Product product, Integer paymentMethod, Integer quantity) {
        int qty = quantity == null || quantity < 0 ? 0 : quantity;
        return calcUnitPrice(product, paymentMethod).multiply(BigDecimal.valueOf(qty));
    }
}
