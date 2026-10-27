package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.StationDeliveryConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.service.DeliveryFeeService;
import com.example.aquaflow.util.BarrelScope;
import com.example.aquaflow.util.DeliveryFeeUtil;
import com.example.aquaflow.util.PriceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送金额条件的可选建议。桶数条件只作用于含桶装水的订单；已保存的 null 金额
 * 表示没有金额条件，明确的0也保留原计算语义。建议值不是上架、保存或报价的门槛。
 */
@Service
public class DeliveryConfigGuideService {

    /** 起送量建议 ≈ 两桶水的钱 */
    public static final int MIN_ORDER_BUCKETS_EQUIVALENT = 2;
    /** 免运费建议 ≈ 三桶水的钱 */
    public static final int FREE_DELIVERY_BUCKETS_EQUIVALENT = 3;

    @Autowired
    private StationDeliveryConfigMapper stationDeliveryConfigMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 配置引导：缺哪些金额字段、建议值与依据。
     *
     * <p>返回字段（前端不得自造同义字段）：</p>
     * <ul>
     *   <li>{@code nonBarrelOnShelf} —— 本站是否已上架非桶装商品（决定要不要提示这件事）；</li>
     *   <li>{@code requiredAmountFields} —— 缺的金额字段名列表（{@code minOrderAmount} / {@code freeDeliveryAmount}）；</li>
     *   <li>{@code cheapestWaterPrice} / {@code cheapestPriceBasis} —— 建议值的依据（哪来的价）；</li>
     *   <li>{@code suggestedMinOrderAmount} / {@code suggestedFreeDeliveryAmount} —— 建议值，可为 null。</li>
     * </ul>
     */
    public Map<String, Object> guidance(Long stationId) {
        StationDeliveryConfig cfg = stationDeliveryConfigMapper.getByStationId(stationId);
        if (cfg == null) {
            cfg = StationDeliveryConfig.defaults(stationId);
        }

        boolean nonBarrelOnShelf = hasNonBarrelOnShelf(stationId);
        List<String> required = missingAmountFields(cfg);
        BigDecimal cheapestWater = cheapestOnShelfPrice(stationId, true);
        BigDecimal cheapestAny = cheapestWater != null ? cheapestWater : cheapestOnShelfPrice(stationId, false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nonBarrelOnShelf", nonBarrelOnShelf);
        // 兼容字段名 requiredAmountFields，当前仅表达可选金额建议，不作为就绪门禁
        data.put("requiredAmountFields", required);
        data.put("cheapestWaterPrice", cheapestWater);
        data.put("cheapestPrice", cheapestAny);
        data.put("cheapestPriceBasis", cheapestWater != null ? "本站已上架桶装水最低价"
                : (cheapestAny != null ? "本站已上架商品最低价（没有桶装水）" : null));
        data.put("suggestedMinOrderAmount",
                required.contains("minOrderAmount") ? suggest(cheapestAny, MIN_ORDER_BUCKETS_EQUIVALENT) : null);
        data.put("suggestedFreeDeliveryAmount",
                required.contains("freeDeliveryAmount") ? suggest(cheapestAny, FREE_DELIVERY_BUCKETS_EQUIVALENT) : null);
        return data;
    }

    /**
     * 上架非桶装商品前的硬校验：**配了桶数门槛、却没有对应的金额门槛**时拒绝，并把建议值写进文案。
     *
     * <p>为什么不干脆静默兜底一个金额：那会悄悄改变本站所有订单的计费口径（老客户突然被拦或被加钱），
     * 属于"改了别人的钱却不告诉他"。宁可当场拒绝、让站长自己确认一个数 —— 界面给了建议值，
     * 一秒钟就能填完。</p>
     */
    public void requireAmountFieldsForNonBarrel(Long stationId) {
        StationDeliveryConfig cfg = stationDeliveryConfigMapper.getByStationId(stationId);
        if (cfg == null) {
            return;   // 没配过 = 两条规则都没启用 → 非桶装单本就不受门槛约束，无需拦
        }
        List<String> missing = missingAmountFields(cfg);
        if (missing.isEmpty()) {
            return;
        }
        Map<String, Object> guide = guidance(stationId);
        StringBuilder sb = new StringBuilder("上架瓶装水/一次性桶/饮水器之前，请先补上金额门槛：");
        if (missing.contains("minOrderAmount")) {
            sb.append("起送量金额");
            Object s = guide.get("suggestedMinOrderAmount");
            if (s != null) {
                sb.append("（建议 ¥").append(s).append("，约两桶水）");
            }
        }
        if (missing.contains("freeDeliveryAmount")) {
            if (missing.contains("minOrderAmount")) {
                sb.append("、");
            }
            sb.append("免运费金额");
            Object s = guide.get("suggestedFreeDeliveryAmount");
            if (s != null) {
                sb.append("（建议 ¥").append(s).append("，约三桶水）");
            }
        }
        sb.append("。原因：非桶装商品不占桶，桶数门槛对它没有意义，只配桶数等于这条规则对那类订单不生效。");
        sb.append("去「配送计费」页填写即可（页面已给出建议值，一键填入）。");
        throw new BusinessException(sb.toString());
    }

    /** 可建议的金额字段：仅对应桶数条件已设置且金额为 null；0 是已知配置。 */
    public List<String> missingAmountFields(StationDeliveryConfig cfg) {
        List<String> missing = new ArrayList<>();
        if (positive(cfg.getMinOrderBuckets()) && cfg.getMinOrderAmount() == null) {
            missing.add("minOrderAmount");
        }
        if (positive(cfg.getFreeDeliveryBuckets()) && cfg.getFreeDeliveryAmount() == null) {
            missing.add("freeDeliveryAmount");
        }
        return missing;
    }

    /** 本站是否已上架（enabled=1）任何非桶装商品 —— 决定要不要对站长提这件事。 */
    public boolean hasNonBarrelOnShelf(Long stationId) {
        for (Inventory inv : inventoryMapper.listByStationId(stationId)) {
            if (inv == null || !Integer.valueOf(1).equals(inv.getEnabled()) || inv.getProductId() == null) {
                continue;
            }
            Product p = productMapper.getById(inv.getProductId());
            if (p != null && Integer.valueOf(1).equals(p.getStatus()) && !BarrelScope.isBarrel(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本站已上架商品的**最低单价**（{@code barrelOnly=true} 时只看桶装水）。
     * 走 {@link PriceUtil#calcUnitPrice}（站级售价覆盖优先）—— 建议值必须与客户实际看到的价格同口径。
     */
    private BigDecimal cheapestOnShelfPrice(Long stationId, boolean barrelOnly) {
        BigDecimal cheapest = null;
        for (Inventory inv : inventoryMapper.listByStationId(stationId)) {
            if (inv == null || !Integer.valueOf(1).equals(inv.getEnabled()) || inv.getProductId() == null) {
                continue;
            }
            Product p = productMapper.getById(inv.getProductId());
            if (p == null || (barrelOnly && !BarrelScope.isBarrel(p))) {
                continue;
            }
            BigDecimal price = PriceUtil.calcUnitPrice(p, inv, null);
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (cheapest == null || price.compareTo(cheapest) < 0) {
                cheapest = price;
            }
        }
        return cheapest;
    }

    private static BigDecimal suggest(BigDecimal unitPrice, int buckets) {
        if (unitPrice == null) {
            return null;
        }
        // 取整到元：建议值不该出现 36.00 这种"精确到分"的数，站长看得懂才敢用
        return unitPrice.multiply(BigDecimal.valueOf(buckets)).setScale(0, RoundingMode.HALF_UP);
    }

    private static boolean positive(Integer v) {
        return v != null && v > 0;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }
}
