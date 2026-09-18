package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.dto.StationCatalogSettingDTO;
import com.example.aquaflow.dto.StationStockDTO;
import com.example.aquaflow.service.CatalogService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长「选品与本站设置」（<b>站长专属</b>）。
 *
 * <p>产品模型见 {@code docs/design/12-商品与库存重构.md}：</p>
 * <ul>
 *   <li>打开商品页第一眼是<b>通用商品库</b>的选项列表（"选什么水"），站长从这里"选用"；</li>
 *   <li>站长能改的只有<b>本站</b>的东西：上架 / 库存 / 本站售价 / 本站押金 / 水票开关与价格 / 优先展示；</li>
 *   <li>商品的名称 / 规格 / 图片 / 描述由开发者维护，<b>站长改不了</b>（自定义商品除外，见
 *       {@code /api/manager/my-products}）；</li>
 *   <li>水站由后端按登录态判定（{@link AuthContext#requireStationId()}），<b>不信任请求参数</b>。</li>
 * </ul>
 *
 * <p>顾客端<b>不可</b>调用本类任何端点（类级 {@code @RequireRole("STATION_MANAGER")}）。</p>
 */
@RestController
@RequestMapping("/api/manager/catalog")
@RequireRole("STATION_MANAGER")
public class ManagerCatalogController {

    @Autowired
    private CatalogService catalogService;

    /** 选品列表：通用库 + 本站自定义，带本站状态（是否已选用、上架、库存、本站价、水票、优先展示）。 */
    @GetMapping
    public Result<List<ProductWithInventoryVO>> list() {
        // 图片 URL 由 CatalogService 统一注入（两处列表接口共用一份实现）
        return Result.success(catalogService.listCatalog(AuthContext.requireStationId()));
    }

    /**
     * 平台预设商品图清单（站长选图用）。
     *
     * <p>站长建自定义商品时，图片不再是"只能自己拍一张传"——也可以从平台预设里选一张。
     * 返回 {@code [{key, path}]}，其中 {@code key} 是稳定标识、{@code path} 是小程序包内资源路径
     * （前端可直接当 {@code src} 用）。映射真值在 {@code constant/ProductImageKeys}。</p>
     *
     * <p><b>为什么由后端下发而不是前端硬编码：</b>两端小程序的包内资源互相独立、
     * 将来还可能整体换成 COS 地址；前端硬编码路径会导致"换存储要改两个小程序"。</p>
     */
    @GetMapping("/preset-images")
    public Result<List<Map<String, String>>> presetImages() {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        com.example.aquaflow.constant.ProductImageKeys.all().forEach((key, path) -> {
            Map<String, String> item = new HashMap<>();
            item.put("key", key);
            item.put("path", path);
            list.add(item);
        });
        return Result.success(list);
    }

    /**
     * 选用某商品到本站（新建本站配置行）。
     * <p>默认<b>未上架</b>（{@code enabled=0}）：选了不等于开卖，站长还要填库存再上架。</p>
     */
    @PostMapping("/{productId}/select")
    public Result<Map<String, Object>> select(@PathVariable Long productId,
                                             @RequestBody(required = false) StationCatalogSettingDTO dto) {
        Long stationId = AuthContext.requireStationId();
        List<String> warnings = catalogService.saveStationSetting(stationId, productId,
                dto != null ? dto : new StationCatalogSettingDTO(), true);
        return withWarnings(warnings);
    }

    /** 更新本站设置（patch：未传的字段保持原值；传 0 表示清除价格覆盖）。 */
    @PutMapping("/{productId}")
    public Result<Map<String, Object>> updateSetting(@PathVariable Long productId,
                                                     @RequestBody StationCatalogSettingDTO dto) {
        Long stationId = AuthContext.requireStationId();
        return withWarnings(catalogService.saveStationSetting(stationId, productId, dto, false));
    }

    /** 移除本站配置（不再卖）：库存必须先盘点为 0，否则报错。 */
    @DeleteMapping("/{productId}")
    public Result<Void> remove(@PathVariable Long productId) {
        catalogService.removeFromStation(AuthContext.requireStationId(), productId);
        return Result.success();
    }

    /** 盘点：把本站库存设为 target，差额写 ADJUST 流水；入库（加数量）用 {@code POST /api/inventory/inbound}。 */
    @PostMapping("/{productId}/stock")
    public Result<Map<String, Object>> setStock(@PathVariable Long productId, @RequestBody StationStockDTO dto) {
        Long stationId = AuthContext.requireStationId();
        int delta = catalogService.setStock(stationId, productId, dto.getTarget(), dto.getNote());
        Map<String, Object> data = new HashMap<>();
        data.put("delta", delta);
        return Result.success(data);
    }

    /** 站级价提醒（非阻断）统一用这个形状返回，前端只做提示、不当失败处理。 */
    private Result<Map<String, Object>> withWarnings(List<String> warnings) {
        Map<String, Object> data = new HashMap<>();
        data.put("warnings", warnings == null ? List.of() : warnings);
        return Result.success(data);
    }
}
