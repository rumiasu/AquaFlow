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
}
