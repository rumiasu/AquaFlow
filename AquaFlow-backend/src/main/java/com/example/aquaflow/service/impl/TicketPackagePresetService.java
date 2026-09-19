package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.PriceUtil;
import com.example.aquaflow.util.TicketPreset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一水票的**预设档引导**（2026-09-19，v54）：把"统一票该卖多少钱"从站长脑子里挪到系统里。
 *
 * <p>纯规则在 {@link TicketPreset}（张数与折扣），本类只负责一件事：
 * <b>算出"本站一桶水的钱"当基准</b>，然后把预设档折算成可直接保存的档位（张数 / 总价 / 均价 / 名称）。</p>
 *
 * <p><b>基准价口径</b>（与 {@code DeliveryConfigGuideService} 建议值同一手法，站长一眼看得懂）：
 * 取本站**已上架桶装水**（{@code category=1}）里最便宜的那一桶的单价 —— 统一票是"1 张 = 1 桶"，
 * 所以基准就是"一桶水的钱"；单价走 {@link PriceUtil#calcUnitPrice} 传 {@code TICKET} 的那一级
 * （站级水票价 → 通用库水票价 → 零售价），与客户**用票抵扣时订单水费的计算口径同源**。
 * 「依据」文案按**最终胜出的那个商品**判定（配过水票价 vs 只能按零售价），见
 * {@link #cheapestOnShelfBarrelPrice}。本站没有已上架桶装水 → **不给建议**
 * （回 {@code basePriceBasis=null} 与空数组，让前端提示"请先上架桶装水或自己填价"，
 * 而不是塞一个凭空捏造的数）。
 *
 * <p>⚠️ 本类**只读**：绝不自动创建档位。原因见 {@link TicketPreset} 的注释 ——
 * "本站有上架的 {@code product_id=0} 档位"就是统一水票的站级开关，自动落行等于替水站开通折扣工具。</p>
 */
@Service
public class TicketPackagePresetService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 预设档引导。
     *
     * <p>返回字段（前端不得自造同义字段）：</p>
     * <ul>
     *   <li>{@code baseUnitPrice} —— "本站一桶水的钱"，可为 null；</li>
     *   <li>{@code basePriceBasis} —— 这个价哪来的（文案），null 表示取不到、不建议一键填入；</li>
     *   <li>{@code presets} —— 预设档数组，每项 {@code {qty, price, unitPrice, discountText, discountPerMille, title}}；
     *       取不到基准价时为空数组（**不是**给一堆 0 元的档）；</li>
     *   <li>{@code productId} —— 恒为 {@code 0}，提示前端"这些档位是站级通用票的档位"。</li>
     * </ul>
     */
    public Map<String, Object> presets(Long stationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", com.example.aquaflow.util.TicketScope.UNIFIED_PRODUCT_ID);

        BasePrice base = cheapestOnShelfBarrelPrice(stationId);
        data.put("baseUnitPrice", base != null ? base.unitPrice() : null);
        data.put("basePriceBasis", base != null ? base.basis() : null);

        List<Map<String, Object>> presets = new ArrayList<>();
        if (base != null) {
            for (TicketPreset.Tier tier : TicketPreset.tiers()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("qty", tier.qty());
                item.put("discountPerMille", tier.discountPerMille());
                item.put("discountText", TicketPreset.discountText(tier.discountPerMille()));
                item.put("price", TicketPreset.totalPrice(base.unitPrice(), tier));
                item.put("unitPrice", TicketPreset.unitPrice(base.unitPrice(), tier));
                item.put("title", TicketPreset.title(tier));
                presets.add(item);
            }
        }
        data.put("presets", presets);
        return data;
    }

    /** 基准价 + 它是怎么来的（文案要能直接给站长看）。 */
    private record BasePrice(BigDecimal unitPrice, String basis) {}

    /**
     * 本站"一桶水的钱" = 已上架**桶装水**里最便宜的那一桶。
     *
     * <p>取 {@link PriceUtil#calcUnitPrice} 传 {@code TICKET} 的那一级（站级水票价 → 通用库水票价 → 零售价）：
     * 因为客户用统一票抵扣时，订单水费正是按这一级算的（见 {@code PaymentServiceImpl.quote}），
     * <b>建议值必须与"这张票真正抵掉的钱"同源</b>，否则站长按建议价卖票就会亏。</p>
     *
     * <p>「依据」按**最终胜出的那个商品**判定（{@link PriceUtil#hasEffectiveTicketPrice}），
     * 不是"本站有任何一个商品配过水票价" —— 混合情况下前者才是事实：
     * A 配了水票价 15、B 没配（零售 12），最便宜的是 B 的 12，依据就该说"零售价"。</p>
     *
     * <p>只看 {@code enabled=1} 且 {@link BarrelScope#isBarrel}：统一票只抵桶装水，
     * 拿瓶装水/饮水器的价当"一桶水的钱"会把基准拉低到离谱。
     * 本站没有已上架桶装水 → 返回 {@code null}（**不给建议**，而不是塞一个凭空捏造的数）。</p>
     */
    private BasePrice cheapestOnShelfBarrelPrice(Long stationId) {
        BigDecimal cheapest = null;
        boolean cheapestUsesTicketPrice = false;
        for (Inventory inv : inventoryMapper.listByStationId(stationId)) {
            if (inv == null || !Integer.valueOf(1).equals(inv.getEnabled()) || inv.getProductId() == null) {
                continue;
            }
            Product p = productMapper.getById(inv.getProductId());
            if (!BarrelScope.isBarrel(p)) {
                continue;
            }
            BigDecimal price = PriceUtil.calcUnitPrice(p, inv, PayMethod.TICKET);
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (cheapest == null || price.compareTo(cheapest) < 0) {
                cheapest = price;
                cheapestUsesTicketPrice = PriceUtil.hasEffectiveTicketPrice(p, inv);
            }
        }
        if (cheapest == null) {
            return null;
        }
        return new BasePrice(cheapest, cheapestUsesTicketPrice
                ? "本站已上架桶装水的最低水票价"
                : "本站已上架桶装水的最低零售价（还没配水票价）");
    }
}
