package com.example.aquaflow.util;

import com.example.aquaflow.entity.Product;

/**
 * 「这个商品是不是桶（循环容器）」—— 全系统唯一判据。
 *
 * <p><b>为什么要有这个类</b>：押金与桶账<strong>只对桶装水生效</strong>，这条口径写死在两处列注释里：</p>
 * <ul>
 *   <li>{@code product.deposit}：<i>"押金(只有桶装水使用; …)"</i></li>
 *   <li>{@code inventory.deposit_price}：<i>"本站押金(仅桶装水使用)…"</i></li>
 * </ul>
 *
 * <p>但代码各处历史写法是<b>反选</b> —— {@code category != 1} 就当"非桶装"处理。
 * 反选本身没错，错在它把"桶装水"这件事散落在六七个文件里各写一遍，于是长出了两条偏离设计的路
 * （2026-09-19 实测并修复）：</p>
 * <ol>
 *   <li><b>非桶装也收押金</b>：报价与下单都对 {@code category != 1} 按件收押金，而押金唯一出口是
 *       "按桶型押金条退桶"——非桶装没有押金条，钱进了押金账户却没有自动退还路径；</li>
 *   <li><b>非桶装污染桶账</b>：{@code BarrelLedgerService.applyDelivery} 的"本单送出"按订单明细统计、
 *       不看 category，非桶装商品会被记进 {@code customer_barrel_over}，虚增欠桶并生成假桶异常单。</li>
 * </ol>
 *
 * <p>所以：<b>凡是要判断"要不要收押金 / 要不要进桶账 / 算不算桶数"的地方，一律调本类，
 * 不要在调用点写 {@code category == 1} 或 {@code category != 1}</b> —— 判据只留一处，
 * 将来若要支持"循环箱装水"之类的新形态，改这里一处即可（那时才需要引入 {@code reuse_mode} 维度）。</p>
 *
 * <p>⚠️ 本类只管<b>品类归属</b>，不改变任何金额算法：押金单价仍走 {@code PriceUtil.calcDeposit}。</p>
 */
public final class BarrelScope {

    private BarrelScope() {}

    /** 桶装水品类值（正本：{@code product.category} 注释 —— 1 桶装水 / 2 瓶装水 / 3 饮水器）。 */
    public static final int CATEGORY_BARREL = 1;

    /** 该品类是否属于桶（押金 / 桶账 / 桶数口径都只认它）。 */
    public static boolean isBarrelCategory(Integer category) {
        return category != null && category == CATEGORY_BARREL;
    }

    /** 该商品是否属于桶；{@code null} 商品按"不是桶"处理（拿不准就不收押金、不进桶账）。 */
    public static boolean isBarrel(Product product) {
        return product != null && isBarrelCategory(product.getCategory());
    }
}
