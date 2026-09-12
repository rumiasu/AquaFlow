package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.dto.ManagerProductDTO;
import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端"商品 + 库存"统一管理接口.
 */
@Slf4j
@RestController
@RequestMapping("/api/manager/products")
@RequireRole("STATION_MANAGER")
public class ManagerProductController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private CosUtil cosUtil;

    // ==================== 查: 商品列表 (含当前水站库存配置) ====================

    @GetMapping
    public Result<List<ProductWithInventoryVO>> list(@RequestParam(required = false) Integer status) {
        Long stationId = AuthContext.requireStationId();
        List<ProductWithInventoryVO> list = productMapper.listWithInventory(stationId);
        if (status != null) {
            list.removeIf(vo -> vo.getStatus() == null || vo.getStatus() != status);
        }
        // 为每条记录注入图片临时访问 URL
        for (ProductWithInventoryVO vo : list) {
            injectImageUrl(vo);
        }
        return Result.success(list);
    }

    // ==================== 查: 单个商品 (含库存配置) ====================

    @GetMapping("/{id}")
    public Result<ProductWithInventoryVO> getById(@PathVariable Long id) {
        Long stationId = AuthContext.requireStationId();
        ProductWithInventoryVO vo = productMapper.getByIdWithInventory(id, stationId);
        if (vo == null) {
            return Result.error("商品不存在");
        }
        injectImageUrl(vo);
        return Result.success(vo);
    }

    // ==================== 增: 创建商品 + 同时配置库存/上架/水票 ====================

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Long> save(@RequestBody @Valid ManagerProductDTO.Save params) {
        Long stationId = AuthContext.requireStationId();

        // --- 商品基本信息 ---
        Product product = new Product();
        product.setName(params.getName());
        product.setCategory(params.getCategory());
        product.setBrand(params.getBrand());
        product.setSpec(params.getSpec());
        // 前端传的是 objectName（从 /api/common/upload 返回的 URL 中解析出来的）
        // 或者前端直接传 objectName
        product.setImageObjectName(params.getImageObjectName());
        if (product.getImageObjectName() == null && params.getImageUrl() != null) {
            // 兼容旧格式：前端可能还在传 imageUrl，提取 objectName
            product.setImageObjectName(extractObjectName(params.getImageUrl()));
        }
        product.setDescription(params.getDescription());
        product.setPrice(params.getPrice());
        product.setDeposit(params.getDeposit());
        product.setMaxPerOrder(params.getMaxPerOrder());
        product.setStatus(1);
        product.setSort(params.getSort() != null ? params.getSort() : 0);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());

        if (product.getDeposit() == null) {
            product.setDeposit(BigDecimal.ZERO);
        }

        productMapper.insert(product);

        // --- 同时配置当前水站的库存/上架/水票 ---
        if (params.getQuantity() != null || params.getEnabled() != null
                || params.getTicketEnabled() != null || params.getTicketPrice() != null
                || params.getPriorityDisplay() != null) {
            Integer quantity = params.getQuantity() != null ? params.getQuantity() : 0;
            Integer enabled = params.getEnabled() != null ? params.getEnabled() : 1;
            Integer ticketEnabled = params.getTicketEnabled() != null ? params.getTicketEnabled() : 0;
            BigDecimal ticketPrice = params.getTicketPrice() != null ? params.getTicketPrice() : BigDecimal.ZERO;
            Integer priorityDisplay = params.getPriorityDisplay() != null ? params.getPriorityDisplay() : 0;
            inventoryMapper.upsertSettings(stationId, product.getId(), quantity, enabled, ticketEnabled, ticketPrice, priorityDisplay);
        }

        return Result.success(product.getId());
    }

    // ==================== 改: 更新商品基本信息 ====================

    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid ManagerProductDTO.Update params) {
        Product product = productMapper.getById(id);
        if (product == null) {
            return Result.error("商品不存在");
        }

        if (params.getName() != null) product.setName(params.getName());
        if (params.getCategory() != null) product.setCategory(params.getCategory());
        if (params.getBrand() != null) product.setBrand(params.getBrand());
        if (params.getSpec() != null) product.setSpec(params.getSpec());
        if (params.getImageObjectName() != null) {
            product.setImageObjectName(params.getImageObjectName());
        } else if (params.getImageUrl() != null) {
            // 兼容旧格式
            product.setImageObjectName(extractObjectName(params.getImageUrl()));
        }
        if (params.getDescription() != null) product.setDescription(params.getDescription());
        if (params.getPrice() != null) product.setPrice(params.getPrice());
        if (params.getDeposit() != null) product.setDeposit(params.getDeposit());
        if (params.getMaxPerOrder() != null) product.setMaxPerOrder(params.getMaxPerOrder());
        if (params.getStatus() != null) product.setStatus(params.getStatus());
        if (params.getSort() != null) product.setSort(params.getSort());
        product.setUpdateTime(LocalDateTime.now());

        productMapper.update(product);

        // 同时更新库存配置
        Long stationId = AuthContext.requireStationId();
        if (params.getQuantity() != null || params.getEnabled() != null
                || params.getTicketEnabled() != null || params.getTicketPrice() != null
                || params.getPriorityDisplay() != null) {
            Integer quantity = params.getQuantity() != null ? params.getQuantity() : 0;
            Integer enabled = params.getEnabled() != null ? params.getEnabled() : 1;
            Integer ticketEnabled = params.getTicketEnabled() != null ? params.getTicketEnabled() : 0;
            BigDecimal ticketPrice = params.getTicketPrice() != null ? params.getTicketPrice() : BigDecimal.ZERO;
            Integer priorityDisplay = params.getPriorityDisplay() != null ? params.getPriorityDisplay() : 0;
            inventoryMapper.upsertSettings(stationId, id, quantity, enabled, ticketEnabled, ticketPrice, priorityDisplay);
        }

        return Result.success();
    }

    // ==================== 删: 软删除 ====================

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Product product = productMapper.getById(id);
        if (product == null) {
            return Result.error("商品不存在");
        }
        productMapper.deleteById(id);
        return Result.success();
    }

    // ==================== 上架/下架 快捷切换 ====================

    @RequestMapping(value = "/{id}/shelf", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<Void> toggleShelf(@PathVariable Long id, @RequestParam Integer enabled) {
        Long stationId = AuthContext.requireStationId();
        if (inventoryMapper.getByStationAndProduct(stationId, id) == null) {
            inventoryMapper.upsertSettings(stationId, id, 0, enabled, 0, java.math.BigDecimal.ZERO, 0);
        } else {
            var inv = inventoryMapper.getByStationAndProduct(stationId, id);
            inventoryMapper.upsertSettings(stationId, id, inv.getQuantity(), enabled,
                    inv.getTicketEnabled() != null ? inv.getTicketEnabled() : 0,
                    inv.getTicketPrice() != null ? inv.getTicketPrice() : java.math.BigDecimal.ZERO,
                    inv.getPriorityDisplay() != null ? inv.getPriorityDisplay() : 0);
        }
return Result.success();
    }

    // ==================== 优先展示切换 ====================

    private static final int MAX_PRIORITY_DISPLAY = 3;

    @RequestMapping(value = "/{id}/priority", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<Void> togglePriority(@PathVariable Long id, @RequestParam Integer enabled) {
        Long stationId = AuthContext.requireStationId();

        // 开启优先展示时检查数量上限
        if (enabled != null && enabled == 1) {
            int currentCount = inventoryMapper.countPriorityDisplay(stationId);
            // 排除自身（如果已经是优先展示）
            var currentInv = inventoryMapper.getByStationAndProduct(stationId, id);
            if (currentInv != null && currentInv.getPriorityDisplay() != null && currentInv.getPriorityDisplay() == 1) {
                currentCount--;
            }
            if (currentCount >= MAX_PRIORITY_DISPLAY) {
                return Result.error("优先展示商品数量已达上限（最多" + MAX_PRIORITY_DISPLAY + "个）");
            }
        }

        var inv = inventoryMapper.getByStationAndProduct(stationId, id);
        if (inv == null) {
            inventoryMapper.upsertSettings(stationId, id, 0, 1, 0, java.math.BigDecimal.ZERO, enabled);
            inv = inventoryMapper.getByStationAndProduct(stationId, id);
        }
        inventoryMapper.updatePriorityDisplay(inv.getId(), enabled);
        return Result.success();
    }

    // ==================== 库存入库 (批量) ====================

    @PostMapping("/inbound")
    public Result<Void> inbound(@RequestBody InventoryInboundDTO dto) {
        Long stationId = AuthContext.requireStationId();
        inventoryService.inbound(stationId, dto.getItems());
        return Result.success();
    }

    // ==================== 私有辅助 ====================

    /** 为 ProductWithInventoryVO 注入图片临时访问 URL（容错：单条失败不影响列表） */
    private void injectImageUrl(ProductWithInventoryVO vo) {
        try {
            if (vo.getImageObjectName() != null && !vo.getImageObjectName().isEmpty()) {
                vo.setImageUrl(cosUtil.generatePublicUrl(vo.getImageObjectName()));
            }
        } catch (Exception e) {
            log.warn("生成图片URL失败, objectName={}, error={}", vo.getImageObjectName(), e.getMessage());
        }
    }

    /**
     * 从 COS URL 中提取 objectName。
     * 例如 "https://bucket.cos.region.myqcloud.com/public/product/abc.jpg" → "public/product/abc.jpg"
     */
    private String extractObjectName(String url) {
        if (url == null || url.isEmpty()) return null;
        // 如果已经是 objectName（不含 http），直接返回
        if (!url.startsWith("http")) return url;
        // 从 URL 中提取 path 部分
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return path != null && path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return url;
        }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }
}
