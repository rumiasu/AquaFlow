package com.example.aquaflow.service;

import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerBarrelAsset;

import java.util.List;
import java.util.Map;

public interface BarrelService {

    List<CustomerBarrelAsset> getAssets(Long customerId, Long stationId);

    List<BarrelRecord> listRecords(Long customerId, Long stationId);

    void handleBarrelException(Long customerId, Long stationId, Long productId, Integer type, Integer quantity, Long relatedOrderId, String note, Long operatorId);

    /**
     * 站长审批退桶申请
     * @param id          退桶记录ID
     * @param status      2=确认收到空桶 3=已退押金 4=驳回
     * @param handleNote  处理备注
     * @param operatorId  站长ID
     */
    void handleBarrelReturn(Long id, Integer status, String handleNote, Long operatorId);

    /**
     * 获取按水类型分组的桶资产摘要（用于首页展示）
     */
    List<Map<String, Object>> getBarrelSummaryByType(Long customerId, Long stationId);
}
