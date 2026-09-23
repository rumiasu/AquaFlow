package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.StationTicketDiscount;
import com.example.aquaflow.entity.TicketPackage;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationTicketDiscountMapper;
import com.example.aquaflow.mapper.TicketPackageMapper;
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
 * 水票**档位**的唯一入口：客户端该看到哪些档位、某一档该卖多少钱。
 *
 * <p><b>本类是"定制 or 统一"这条判据的唯一实现</b>（同 {@code util/BarrelScope} 的收敛做法）：
 * 别在 Controller / 前端 / 别处再写一遍，否则"该商品走定制还是走统一"会长出第二套答案。</p>
 *
 * <p><b>判据链</b>（2026-09-20 产品澄清后的定稿形态）：</p>
 * <ol>
 *   <li>该商品在本站**没上架** → 没有任何档位；</li>
 *   <li>该商品**开通了定制票**（{@code inventory.ticket_enabled = 1} 且水票价 &gt; 0）
 *       → 用站长给它挂的**自定义档位**（{@code ticket_package}，绝对价目表）。
 *       ⚠️ <b>定制优先</b>：即使本站也配了统一折扣，这一款水也走定制那套；</li>
 *   <li>否则若该商品是**桶装水**（{@code category=1}，与押金/桶账同口径）且本站**配了上架的统一折扣档**
 *       → 用统一折扣：<b>价格 = 该款水自己的水票价 × 折扣</b>（这就是产品说的「对应水怎么统一打折」）；</li>
 *   <li>其余 → 没有档位（该商品不能用票）。</li>
 * </ol>
 *
 * <p>⚠️ <b>统一折扣是"按档"的，不是"按单张"的</b>：折扣本身长在张数档上（买 10 张 9.5 折、
 * 买 30 张 9 折）。所以走统一折扣的商品**只有档位可买**（没有"单张统一价"这种东西 ——
 * 那正是 v54 做错的形态）。</p>
 *
 * <p>⚠️ 价格一律在本类现算、**不落库**：落库就会与"各款水的价"分叉。</p>
 */
@Service
public class TicketTierService {

    /** 档位来源：站长给这款水挂的绝对价目表 */
    public static final String SOURCE_CUSTOM = "CUSTOM";
    /** 档位来源：站级统一折扣按该款水的价折算 */
    public static final String SOURCE_UNIFIED = "UNIFIED";

    @Autowired
    private TicketPackageMapper ticketPackageMapper;

    @Autowired
    private StationTicketDiscountMapper stationTicketDiscountMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private ProductMapper productMapper;

    /** 本站是否配了**上架**的统一折扣档 —— "统一折扣是否生效"的唯一判据（不另设开关列）。 */
    public boolean unifiedConfigured(Long stationId) {
        return stationId != null && stationTicketDiscountMapper.countOnShelf(stationId) > 0;
    }

    /** 该商品在本站是否走**定制**（= 与下单闸门、扣票路径同一判据）。 */
    public boolean usesCustomTicket(Product product, Inventory inv) {
        if (product == null || inv == null || !Integer.valueOf(1).equals(inv.getEnabled())) {
            return false;
        }
        boolean enabled = Integer.valueOf(1).equals(inv.getTicketEnabled());
        BigDecimal price = inv.getTicketPrice();
        return enabled && price != null && price.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 客户端该看到的档位（统一形状，前端不必分两套渲染）。
     *
     * <p>每项：{@code {source, packageId, qty, price, unitPrice, discountPerMille?, discountText?, title}}。
     * 定制档带 {@code packageId}（购买时回传），统一档带 {@code discountPerMille}（购买时回传 qty）。</p>
     */
    public List<Map<String, Object>> customerTiers(Long stationId, Long productId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stationId == null || productId == null) {
            return out;
        }
        Product product = productMapper.getById(productId);
        Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
        if (product == null || inv == null || !Integer.valueOf(1).equals(inv.getEnabled())) {
            return out;
        }

        if (usesCustomTicket(product, inv)) {
            for (TicketPackage pkg : ticketPackageMapper.listOnShelf(stationId, productId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", SOURCE_CUSTOM);
                item.put("packageId", pkg.getId());
                item.put("qty", pkg.getQty());
                item.put("price", pkg.getPrice());
                item.put("unitPrice", pkg.getUnitPrice());
                item.put("title", pkg.getTitle() != null ? pkg.getTitle() : (pkg.getQty() + " 张"));
                out.add(item);
            }
            return out;
        }

        // 统一折扣只覆盖桶装水：瓶装水/一次性桶/饮水器不占桶、没有"循环"，
        // 与押金/桶账/回桶核对同一口径（判据唯一实现在 util/BarrelScope）。
        if (!BarrelScope.isBarrel(product)) {
            return out;
        }
        BigDecimal base = PriceUtil.calcUnitPrice(product, inv, PayMethod.TICKET);
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            return out;
        }
        for (StationTicketDiscount d : stationTicketDiscountMapper.listOnShelf(stationId)) {
            TicketPreset.Tier tier = new TicketPreset.Tier(d.getQty(), d.getDiscountPerMille());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", SOURCE_UNIFIED);
            item.put("packageId", null);
            item.put("qty", d.getQty());
            item.put("discountPerMille", d.getDiscountPerMille());
            item.put("discountText", TicketPreset.discountText(d.getDiscountPerMille()));
            item.put("price", TicketPreset.totalPrice(base, tier));
            item.put("unitPrice", TicketPreset.unitPrice(base, tier));
            item.put("title", d.getTitle() != null ? d.getTitle() : TicketPreset.title(tier));
            out.add(item);
        }
        return out;
    }

    /** 购买时按张数取一档**上架的统一折扣**；不存在就抛可读的业务错误（不要静默换价）。 */
    public StationTicketDiscount requireUnifiedTier(Long stationId, Integer qty) {
        if (stationId == null || qty == null || qty <= 0) {
            throw new BusinessException("请选择要购买的统一折扣档位");
        }
        StationTicketDiscount tier = stationTicketDiscountMapper.getOnShelfByQty(stationId, qty);
        if (tier == null) {
            throw new BusinessException("该统一折扣档位不存在或已下架，请刷新后重新选择");
        }
        return tier;
    }

    /**
     * 某个走统一折扣的商品、某一档的**服务端定价**。
     *
     * <p>基准 = 该款水自己的水票价（{@link PriceUtil#calcUnitPrice} 传 {@code TICKET} 那一级：
     * 站级水票价 → 通用库水票价 → 零售价）—— 这就是产品说的「对应水怎么统一打折」；
     * 折扣只在**购买那一刻**固化进批次均价，用票时 1 张就是 1 张。</p>
     */
    public Map<String, BigDecimal> priceUnifiedTier(Product product, Inventory inv, StationTicketDiscount tier) {
        BigDecimal base = PriceUtil.calcUnitPrice(product, inv, PayMethod.TICKET);
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("该商品在本站还没有价格，暂不能按统一折扣买票");
        }
        TicketPreset.Tier t = new TicketPreset.Tier(tier.getQty(), tier.getDiscountPerMille());
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        out.put("baseUnitPrice", base);
        out.put("totalPrice", TicketPreset.totalPrice(base, t));
        out.put("unitPrice", TicketPreset.unitPrice(base, t));
        return out;
    }

    /**
     * 平台预设档（**一键填入的草稿**）：站长不必从空白开始想"买多少张打几折"。
     *
     * <p>⚠️ 只读接口，**不会**替站长建档位 —— 定价是站长的经营决定。
     * 与 v54 的差别：现在预的是**折扣**（张数 + 折扣率），不是总价（总价要按各款水现算，存不下来）。</p>
     */
    public Map<String, Object> presets(Long stationId) {
        List<Map<String, Object>> presets = new ArrayList<>();
        for (TicketPreset.Tier tier : TicketPreset.tiers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("qty", tier.qty());
            item.put("discountPerMille", tier.discountPerMille());
            item.put("discountText", TicketPreset.discountText(tier.discountPerMille()));
            item.put("title", TicketPreset.title(tier));
            presets.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("presets", presets);
        data.put("configured", unifiedConfigured(stationId));
        data.put("note", "折扣按**各款水自己的水票价**折算：农夫山泉按农夫山泉的价、娃哈哈按娃哈哈的价。"
                + "某款水自己配了定制票时以定制票为准。");
        return data;
    }
}
