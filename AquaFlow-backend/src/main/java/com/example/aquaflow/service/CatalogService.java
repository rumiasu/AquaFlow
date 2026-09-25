package com.example.aquaflow.service;

import com.example.aquaflow.dto.ManagerProductDTO;
import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.dto.StationCatalogSettingDTO;
import com.example.aquaflow.entity.ProductSubmission;

import java.util.List;

/**
 * 通用商品库 + 本站配置的读写编排（站长侧）。
 *
 * <p>规格：{@code docs/design/12-商品与库存重构.md}。三条硬规则：</p>
 * <ol>
 *   <li><b>通用库商品站长只读</b>：站长的写操作只能落到 {@code inventory}（本站设置）；</li>
 *   <li><b>自定义商品仅本站可见</b>：{@code owner_station_id} 必须等于调用者的水站，
 *       所有按 id 的操作都要验归属并检查 affected；</li>
 *   <li><b>库存只能走 {@code InventoryService}</b>（写流水），本类不直接改数量列。</li>
 * </ol>
 */
public interface CatalogService {

    /** 选品列表：通用库 + 本站自定义，带本站状态（是否已选用/上架/库存/本站价/水票/优先展示）。 */
    List<ProductWithInventoryVO> listCatalog(Long stationId);

    /**
     * 保存本站设置（"选用"与"改设置"共用）。
     *
     * @return 非阻断提醒（如"本站售价比通用库参考价高 3 倍，请确认"）；保存本身始终成功
     */
    List<String> saveStationSetting(Long stationId, Long productId, StationCatalogSettingDTO dto, boolean selecting);

    /** 移除本站配置（不再卖）：要求本站库存已为 0，否则报错。 */
    void removeFromStation(Long stationId, Long productId);

    /** 盘点：把本站库存设为 target，差额落 ADJUST 流水。 */
    int setStock(Long stationId, Long productId, Integer target, String note);

    /* ===== 本站自定义商品（不入通用库） ===== */

    List<ProductWithInventoryVO> listMyProducts(Long stationId);

    Long createMyProduct(Long stationId, ManagerProductDTO.Save dto);

    List<String> updateMyProduct(Long stationId, Long productId, ManagerProductDTO.Update dto);

    void deleteMyProduct(Long stationId, Long productId);

    /** 上报给开发者，请其考虑纳入通用库；同一商品已有待处理上报时拒绝。 */
    Long submitMyProduct(Long stationId, Long productId, String note);

    /** 本站的上报记录（站长可见自己的）。 */
    List<ProductSubmission> listMySubmissions(Long stationId, int limit);
}
