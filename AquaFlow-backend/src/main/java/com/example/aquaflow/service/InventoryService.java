package com.example.aquaflow.service;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.InventoryRecord;

import java.util.List;

public interface InventoryService {
    List<Inventory> list(Long stationId);

    void inbound(Long stationId, List<InventoryInboundDTO.ItemDTO> items);

    void checkStock(Long stationId, Long productId, Integer needQuantity);

    /**
     * 把本站某商品的库存**设置为目标值**（站长入库/盘点），差额写一条 {@code inventory_record}。
     *
     * <p>为什么必须有这个方法（2026-09-16 商品与库存重构，见 docs/design/12-商品与库存重构.md）：</p>
     * <ul>
     *   <li>原实现里站长在商品编辑弹窗改"库存"是走 {@code InventoryMapper.upsertSettings} 直接覆盖
     *       {@code inventory.quantity}，<b>不写任何流水</b> —— 库存数与 {@code inventory_record}
     *       从此对不上，对账与审计都失效。库存增减必须与流水成对出现（[AQ-029]）。</li>
     *   <li>算差额前先 {@code SELECT ... FOR UPDATE} 锁行，避免并发盘点各自读旧值、最后互相覆盖。</li>
     * </ul>
     *
     * @param targetQuantity 目标库存（&ge;0）
     * @param type           流水类型：入库用 {@code InventoryChangeType.INBOUND}，盘点修正用 {@code ADJUST}
     * @param refId          关联单据ID，可为 null
     * @param note           备注（如"盘点：实盘 12 桶"），可为 null
     * @return 实际写入的差额（可为 0 = 无变化、未写流水）
     */
    int setStock(Long stationId, Long productId, Integer targetQuantity, String type, Long refId, String note);

    /**
     * 保存本站设置（上架 / 本站售价 / 本站押金 / 水票开关与价格 / 优先展示）。
     *
     * <p>⚠️ <b>本方法不改库存</b>：传入行的 {@code quantity} 被忽略，一律沿用当前行的值
     * （行不存在则 0）。库存增减必须走 {@link #setStock}，否则 {@code inventory_record} 会漏记。</p>
     *
     * <p>⚠️ 这是<b>整行覆盖</b>：调用方必须把不打算改的字段取当前行原值填好，
     * 漏传会被写成 NULL/0（历史事故：只改水票价把库存/售价清零）。</p>
     */
    void saveStationSetting(Inventory setting);

    /**
     * [AQ-029] 写一条库存流水。库存增减必须与流水成对出现，供日结对账勾稽。
     *
     * @param stationId  水站ID
     * @param productId  商品ID
     * @param delta      变动量（正=入/回补，负=出/扣减）
     * @param type       变动类型，见 {@link com.example.aquaflow.constant.InventoryChangeType}
     * @param refId      关联单据ID（订单ID等），可为 null
     * @param operatorId 操作人员工ID，可为 null
     * @param note       备注，可为 null
     */
    void recordChange(Long stationId, Long productId, Integer delta, String type,
                      Long refId, Long operatorId, String note);

    /** [AQ-029] 按站查询库存流水（倒序，用于站长查看进出明细） */
    List<InventoryRecord> listRecords(Long stationId, int limit);

    /**
     * 按站 + 商品查询库存流水（倒序）。
     * <p>站长核对"某一种商品"的进出时用；{@code productId} 为 null 等价于 {@link #listRecords(Long, int)}。</p>
     */
    List<InventoryRecord> listRecords(Long stationId, Long productId, int limit);
}
