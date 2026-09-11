package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.service.ProductService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private CosUtil cosUtil;

    @PostMapping
    @RequireRole("STATION_MANAGER")
    public Result save(@RequestBody Product product) {
        resolveImageObjectName(product);
        productService.save(product);
        return Result.success(product.getId());
    }

    @PutMapping("/{id}")
    @RequireRole("STATION_MANAGER")
    public Result update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        resolveImageObjectName(product);
        productService.update(product);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequireRole("STATION_MANAGER")
    public Result delete(@PathVariable Long id) {
        productService.deleteById(id);
        return Result.success();
    }

    @GetMapping
    public Result<List<Product>> list(@RequestParam(required = false) Integer category,
                                      @RequestParam(required = false) String keyword) {
        List<Product> products;
        if (category != null) {
            products = productService.listByCategory(category);
        } else {
            products = productService.list();
        }
        // 关键字搜索：用户端搜索页按名称/品牌/规格匹配。
        // 旧实现完全忽略 keyword，搜索结果恒为"全部商品"，用户以为搜什么都没差别。
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            products = new ArrayList<>(products).stream()
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

    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.error("商品不存在");
        }
        injectImageUrl(product);
        return Result.success(product);
    }

    @GetMapping("/on-sale")
    public Result<List<Product>> listOnSale() {
        List<Product> products = productService.listOnSale();
        injectImageUrls(products);
        return Result.success(products);
    }

    /** 按水站筛选在售商品（客户小程序用） */
    @GetMapping("/sale-by-station")
    public Result<List<Product>> listOnSaleByStation(@RequestParam Long stationId) {
        log.info("[ProductController] listOnSaleByStation called with stationId={}", stationId);
        List<Inventory> inventories = inventoryMapper.listByStationId(stationId);
        log.info("[ProductController] Found {} inventories for station {}", inventories.size(), stationId);
        Set<Long> enabledProductIds = inventories.stream()
                .filter(inv -> inv.getEnabled() != null && inv.getEnabled() == 1)
                .map(Inventory::getProductId)
                .collect(Collectors.toSet());
        log.info("[ProductController] Enabled product IDs: {}", enabledProductIds);
        List<Product> all = productService.listOnSale();
        List<Product> products = all.stream()
                .filter(p -> enabledProductIds.contains(p.getId()))
                .collect(Collectors.toList());
        injectImageUrls(products);
        log.info("[ProductController] Returning {} products for station {}", products.size(), stationId);
        return Result.success(products);
    }

    /** 当前客户已购商品（客户小程序"已有"标签用）— 暂返回空，后续可扩展 */
    @RequireRole({"customer"})
    @GetMapping("/my")
    public Result<List<Product>> listMyProducts() {
        return Result.success(Collections.emptyList());
    }

    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/with-stock")
    public Result<?> listWithStock() {
        Long stationId = AuthContext.requireStationId();
        List<Product> products = productService.list();
        List<Inventory> inventories = inventoryMapper.listByStationId(stationId);
        Map<Long, Inventory> stockMap = inventories.stream()
                .collect(Collectors.toMap(Inventory::getProductId, i -> i, (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : products) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("name", p.getName());
            item.put("brand", p.getBrand());
            item.put("spec", p.getSpec());
            item.put("price", p.getPrice());
            item.put("deposit", p.getDeposit());
            item.put("status", p.getStatus());
            // 注入图片临时访问 URL
            try {
                item.put("imageUrl", p.getImageObjectName() != null
                        ? cosUtil.generatePublicUrl(p.getImageObjectName()) : null);
            } catch (Exception e) {
                log.warn("生成商品图片URL失败, id={}, error={}", p.getId(), e.getMessage());
                item.put("imageUrl", null);
            }
            Inventory inv = stockMap.get(p.getId());
            item.put("stock", inv != null ? inv.getQuantity() : 0);
            item.put("inventoryId", inv != null ? inv.getId() : null);
            result.add(item);
        }
        return Result.success(result);
    }

    /** 为 Product 注入图片临时访问 URL（容错） */
    private void injectImageUrl(Product product) {
        try {
            if (product.getImageObjectName() != null && !product.getImageObjectName().isEmpty()) {
                product.setImageUrl(cosUtil.generatePublicUrl(product.getImageObjectName()));
            }
        } catch (Exception e) {
            log.warn("生成商品图片URL失败, id={}, error={}", product.getId(), e.getMessage());
        }
    }

    /** 批量注入图片临时访问 URL */
    private void injectImageUrls(List<Product> products) {
        for (Product p : products) {
            injectImageUrl(p);
        }
    }

    /** 前端传 imageUrl（presigned URL）时，提取 objectName 存入数据库 */
    private void resolveImageObjectName(Product product) {
        if (product.getImageObjectName() == null && product.getImageUrl() != null && !product.getImageUrl().isEmpty()) {
            product.setImageObjectName(extractObjectName(product.getImageUrl()));
        }
    }

    /** 从 COS URL 中提取 objectName（路径部分） */
    private String extractObjectName(String url) {
        if (url == null || url.isEmpty()) return null;
        if (!url.startsWith("http")) return url;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return path != null && path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return url;
        }
    }
}
