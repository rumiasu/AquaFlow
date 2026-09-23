package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.StationProductVO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.ProductImageResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品接口（<b>浏览侧</b>，顾客/游客可读）。
 *
 * <p><b>2026-09-16 商品与库存重构（docs/design/12-商品与库存重构.md）后的两条规则：</b></p>
 * <ol>
 *   <li><b>写端点已删除</b>：原 {@code POST/PUT/DELETE /api/products} 能让站长建/改<b>全局</b>商品
 *       （站 A 改价，全平台所有站跟着变；站 A 下架，站 B 的商城也没了）。站长现在只有两个写入口：
 *       {@code /api/manager/catalog}（本站设置：上架/价格/库存/水票/优先展示）与
 *       {@code /api/manager/my-products}（本站自定义商品）。通用库商品由开发者/运维维护。</li>
 *   <li><b>可见性带归属</b>：通用库商品人人可见；某站的自定义商品<b>只有该站能读</b>。
 *       所以凡是能拿到 {@code stationId} 的接口都按 {@code (owner_station_id IS NULL OR = stationId)}
 *       过滤 —— 重构前 {@code /api/products/&#123;id&#125;} 不带任何站校验，
 *       一旦有了自定义商品就是跨租户泄露通道。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private ProductImageResolver imageResolver;

    /**
     * 商品列表。
     *
     * @param stationId 可选。传了就返回"通用库 + 该站自定义"；不传只返回通用库商品
     *                  （游客没有站，别站的自定义商品绝不能出现在这里）
     */
    @GetMapping
    public Result<List<Product>> list(@RequestParam(required = false) Integer category,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Long stationId) {
        List<Product> products;
        if (stationId != null) {
            products = productMapper.listVisibleToStation(stationId);
        } else {
            products = productMapper.listOnSale();
        }
        products = new ArrayList<>(products).stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == 1)
                .filter(p -> category == null || category.equals(p.getCategory()))
                .collect(Collectors.toList());
        // 关键字搜索：用户端搜索页按名称/品牌/规格匹配。
        // 旧实现完全忽略 keyword，搜索结果恒为"全部商品"，用户以为搜什么都没差别。
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            products = products.stream()
                    .filter(p -> containsIgnoreCase(p.getName(), kw)
                            || containsIgnoreCase(p.getBrand(), kw)
                            || containsIgnoreCase(p.getSpec(), kw))
                    .collect(Collectors.toList());
        }
        injectImageUrls(products);
        return Result.success(products);
    }

    private static boolean containsIgnoreCase(String src, String lowerKeyword) {
        return src != null && src.toLowerCase().contains(lowerKeyword);
    }

    /**
     * 商品详情。
     *
     * @param stationId 可选，但读本站自定义商品时必须传；<b>同时决定"本站有效价"</b>：
     *                  同一个商品在不同站可以卖不同价，所以详情页的价格也必须带站号，
     *                  否则会出现"列表按本站价、详情按平台价"的不一致。
     */
    @GetMapping("/{id}")
    public Result<StationProductVO> getById(@PathVariable Long id, @RequestParam(required = false) Long stationId) {
        StationProductVO vo = productMapper.getStationProduct(id, stationId);
        if (vo == null) {
            return Result.error("商品不存在");
        }
        if (!platformCatalogVisible(id, stationId)) {
            // 不透露"这个 id 存在但属于别人"：统一按不存在处理
            return Result.error("商品不存在或不属于当前水站");
        }
        injectImageUrl(vo);
        return Result.success(vo);
    }

    /** 通用库在售商品（不带站上下文时用；本站能卖什么一律看 {@link #listOnSaleByStation}）。 */
    @GetMapping("/on-sale")
    public Result<List<Product>> listOnSale() {
        List<Product> products = productMapper.listOnSale();
        injectImageUrls(products);
        return Result.success(products);
    }

    /**
     * 按水站筛选在售商品（客户小程序用）：本站已上架 ∩ 在售 ∩ (通用库或本站自定义)。
     *
     * <p>返回 {@link StationProductVO}，价格是**本站有效价**（站级覆盖 → 通用库参考价），
     * 与报价/下单同口径；并按"优先展示"排序（缺陷 8 的修复点）。</p>
     */
    @GetMapping("/sale-by-station")
    public Result<List<StationProductVO>> listOnSaleByStation(@RequestParam Long stationId) {
        List<StationProductVO> products = productMapper.listSellableByStationWithInventory(stationId);
        for (StationProductVO vo : products) {
            injectImageUrl(vo);
        }
        return Result.success(products);
    }

    /** 站长视角：通用库 + 本站自定义（含下架/停售，便于排查"为什么顾客看不到"）。 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/with-stock")
    public Result<?> listWithStock() {
        Long stationId = AuthContext.requireStationId();
        List<Product> products = productMapper.listVisibleToStation(stationId);
        List<Inventory> inventories = inventoryMapper.listByStationId(stationId);
        Map<Long, Inventory> stockMap = inventories.stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i, (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : products) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("ownerStationId", p.getOwnerStationId());
            item.put("name", p.getName());
            item.put("brand", p.getBrand());
            item.put("spec", p.getSpec());
            item.put("price", p.getPrice());
            item.put("deposit", p.getDeposit());
            item.put("status", p.getStatus());
            // 注入图片 URL（本地预设路径原样下发 / COS 对象键签成临时 URL）
            item.put("imageUrl", imageResolver.resolve(p.getImageObjectName()));
            Inventory inv = stockMap.get(p.getId());
            item.put("stock", inv != null ? inv.getQuantity() : 0);
            item.put("inventoryId", inv != null ? inv.getId() : null);
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 为 Product 注入图片 URL。
     * <p>本地预设路径（{@code /assets/...}）原样下发；COS 对象键签成 24h 临时 URL；
     * 解析失败返回 null 而非抛异常（详情接口不该因一张图挂掉）。</p>
     */
    private void injectImageUrl(Product product) {
        product.setImageUrl(imageResolver.resolve(product.getImageObjectName()));
    }

    /** 为 StationProductVO 注入图片 URL（容错：单条失败不影响列表） */
    private void injectImageUrl(StationProductVO vo) {
        vo.setImageUrl(imageResolver.resolve(vo.getImageObjectName()));
    }

    /**
     * 详情接口的可见性判据：
     * <ul>
     *   <li>通用库商品：在售（status=1）即可见；</li>
     *   <li>本站自定义商品：必须带对的 {@code stationId} 才可见（别站一律按"不存在"处理）；</li>
     *   <li>下架/停售：一律不可见 —— 重构前任何人按 id 都能取到（缺陷 7 的一半）。</li>
     * </ul>
     */
    private boolean platformCatalogVisible(Long id, Long stationId) {
        Product p = productMapper.getById(id);
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            return false;
        }
        return p.getOwnerStationId() == null
                || (stationId != null && stationId.equals(p.getOwnerStationId()));
    }

    /** 批量注入图片临时访问 URL */
    private void injectImageUrls(List<Product> products) {
        for (Product p : products) {
            injectImageUrl(p);
        }
    }
}
