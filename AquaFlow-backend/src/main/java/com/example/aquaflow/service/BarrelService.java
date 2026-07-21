package com.example.aquaflow.service;

import com.example.aquaflow.entity.BarrelRecord;

import java.util.List;
import java.util.Map;

public interface BarrelService {

    Map<String, Object> getSummary(Integer customerId);

    List<BarrelRecord> listRecords(Integer customerId);

    /** 查看所有退桶记录（管理端用） */
    List<BarrelRecord> listAllRecords();

    List<Map<String, Object>> getBarrelSummaryByType(Integer customerId);

    BarrelRecord requestReturn(Integer customerId, Integer quantity, java.math.BigDecimal depositRefund, String note);

    /** 审批退桶记录 */
    void handleReturn(Integer id, Integer status, String handleNote);
}
