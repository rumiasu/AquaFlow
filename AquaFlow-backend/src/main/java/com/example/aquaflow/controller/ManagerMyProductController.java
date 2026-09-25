package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.ManagerProductDTO;
import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.entity.ProductSubmission;
import com.example.aquaflow.service.CatalogService;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长「自己定义商品」（<b>站长专属</b>）。
 *
 * <p>产品口径（{@code docs/design/12-商品与库存重构.md}）：通用商品库不可能覆盖所有水站的所有品，
 * 所以留一个<b>独立入口</b>让站长自定义；但自定义商品</p>
 * <ul>
 *   <li><b>不入通用库</b>（{@code product.owner_station_id = 本站}），别站与顾客都看不到别站的自定义商品；</li>
 *   <li>名称/规格/图片/描述<b>可以</b>编辑（通用库商品这几项是锁死的）；</li>
 *   <li>可以「上报」给开发者，请其考虑纳入通用库（落 {@code product_submission}，由开发者人工处置）。</li>
 * </ul>
 *
 * <p>所有按 id 的操作都先验 {@code owner_station_id} 并检查 affected 行数 —— 通用库商品在这里是"改不动"的。</p>
 */
@RestController
@RequestMapping("/api/manager/my-products")
@RequireRole("STATION_MANAGER")
public class ManagerMyProductController {

    @Autowired
    private CatalogService catalogService;

    @GetMapping
    public Result<List<ProductWithInventoryVO>> list() {
        return Result.success(catalogService.listMyProducts(AuthContext.requireStationId()));
    }

    @PostMapping
    public Result<Long> create(@RequestBody @Valid ManagerProductDTO.Save dto) {
        return Result.success(catalogService.createMyProduct(AuthContext.requireStationId(), dto));
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody ManagerProductDTO.Update dto) {
        List<String> warnings = catalogService.updateMyProduct(AuthContext.requireStationId(), id, dto);
        Map<String, Object> data = new HashMap<>();
        data.put("warnings", warnings == null ? List.of() : warnings);
        return Result.success(data);
    }

    /** 停用（软删，{@code status=0}）：物理行保留，历史订单/桶账/水票的引用不断。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        catalogService.deleteMyProduct(AuthContext.requireStationId(), id);
        return Result.success();
    }

    /** 上报给开发者，请其考虑补进通用库；同一商品已有一条待处理上报时拒绝。 */
    @PostMapping("/{id}/submit")
    public Result<Long> submit(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String note = body != null ? body.get("note") : null;
        return Result.success(catalogService.submitMyProduct(AuthContext.requireStationId(), id, note));
    }

    /** 本站的上报记录（站长只看得到自己的）。 */
    @GetMapping("/submissions")
    public Result<List<ProductSubmission>> submissions(@RequestParam(required = false) Integer limit) {
        return Result.success(catalogService.listMySubmissions(AuthContext.requireStationId(),
                limit == null ? 50 : limit));
    }
}
