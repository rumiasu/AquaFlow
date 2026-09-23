package com.example.aquaflow.util;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;

import java.math.BigDecimal;

/**
 * 计价工具：全系统唯一的单价/押金计算入口。
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
 * <p>
 * 背景三（2026-09-16 商品与库存重构，见 docs/design/12-商品与库存重构.md）：`product` 变成
 * **通用商品库**（参考价），站长可对本站覆盖售价与押金（{@code inventory.sale_price} /
 * {@code deposit_price}）。所以零售价与押金也必须像水票价一样走"站级覆盖 → 通用库参考值"阶梯，
 * 并且**只能在这里实现一次**。
 * </p>
 * <p>
 * ⚠️ 覆盖判定统一为"<b>非空且 &gt; 0</b>"：留空或填 0 都表示用通用库参考值。理由见
 * {@link com.example.aquaflow.entity.Inventory#getDepositPrice()} 的注释（0 押金会与
 * BarrelLedgerService 的 [DEF-2] 兜底口径打架）。
 * </p>
 * 所有涉及单价/押金的地方必须调用本类，禁止各自写 if。
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
     * 无有效站级配置时回退 product.ticket_price；否则取站级零售价（inventory.sale_price），
     * 无有效站级配置时回退通用库参考价 product.price。
     *
     * @param inventory 该站该商品的库存配置（含本站售价/水票价），可为 null
     */
    public static BigDecimal calcUnitPrice(Product product, Inventory inventory, Integer paymentMethod) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (Integer.valueOf(PayMethod.TICKET).equals(paymentMethod)) {
            BigDecimal stTicketPrice = inventory != null ? inventory.getTicketPrice() : null;
            if (isEffectiveOverride(stTicketPrice)) {
                return stTicketPrice;
            }
            if (isEffectiveOverride(product.getTicketPrice())) {
                return product.getTicketPrice();
            }
        }
        // 零售价：本站售价覆盖 → 通用库参考价
        BigDecimal stSalePrice = inventory != null ? inventory.getSalePrice() : null;
        if (isEffectiveOverride(stSalePrice)) {
            return stSalePrice;
        }
        return product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
    }

    /**
     * 计算单桶押金：本站押金覆盖（{@code inventory.deposit_price}）→ 通用库参考押金
     * （{@code product.deposit}）。
     *
     * <p>⚠️ 调用点分两类，别混：</p>
     * <ul>
     *   <li><b>应收/报价/下单</b>（PaymentServiceImpl.quote、OrderServiceImpl.createOrder）：必须用本方法，
     *       并在下单时把结果快照进 {@code orders.deposit_amount} 与
     *       {@code customer_barrel_in_transit.unit_price} —— 快照之后站长再改押金不影响历史单。</li>
     *   <li><b>退押金</b>：一律按 {@code customer_barrel_lot.unit_price}（买入时快照）结算，
     *       <b>不要</b>调本方法重算，否则改价会改到客户已经付过的钱。</li>
     * </ul>
     *
     * <p><b>[2026-09-19] 非桶装商品一律返回 0</b>：押金是"循环桶的押金"，两处列注释都写着
     * <i>仅桶装水使用</i>（见 {@code product.deposit} / {@code inventory.deposit_price}）。
     * 这条判据放在这里而不是各调用点，是因为报价、下单、明细快照、兜底建押金条全都汇到本方法 ——
     * 只要有一处漏判，"非桶装收押金"就会从那个缺口重新长出来（2026-09-19 实测到的正是这种漏）。
     * 品类判据的唯一实现在 {@link BarrelScope}。</p>
     *
     * @param inventory 该站该商品的库存配置（含本站押金），可为 null
     */
    public static BigDecimal calcDeposit(Product product, Inventory inventory) {
        if (!BarrelScope.isBarrel(product)) {
            // 非桶装：即使通用库/站级误填了押金，也不收（写侧另有校验拦截，这里是读侧的最后一道）
            return BigDecimal.ZERO;
        }
        BigDecimal stDeposit = inventory != null ? inventory.getDepositPrice() : null;
        if (isEffectiveOverride(stDeposit)) {
            return stDeposit;
        }
        return product != null && product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO;
    }

    /** 站级覆盖是否生效：非空且 &gt; 0（留空/0 都表示"用通用库参考值"） */
    private static boolean isEffectiveOverride(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 数量 × 单价，空值安全 */
    public static BigDecimal calcItemAmount(Product product, Integer paymentMethod, Integer quantity) {
        int qty = quantity == null || quantity < 0 ? 0 : quantity;
        return calcUnitPrice(product, paymentMethod).multiply(BigDecimal.valueOf(qty));
    }
}
