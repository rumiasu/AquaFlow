package com.example.aquaflow.service;

import com.example.aquaflow.entity.BarrelRecord;
import com.example.aquaflow.entity.CustomerBarrelAsset;

import java.util.List;
import java.util.Map;

/**
 * 水桶业务：客户的桶资产 / 桶流水 / 退桶申请与站长审批。
 *
 * <p><b>⚠️ 这里不是桶账的写入口。</b>桶账（权益批次、欠桶 over、占用）的唯一写入口是
 * {@code BarrelLedgerService}；本接口的退桶审批与试算最终都委托给它，
 * 以保证恒等式「占用 = 权益 + over」不被绕开（over 可为负 = 水站暂存，是合法状态）。
 * 改动本接口时请确认没有直接 UPDATE 余额/数量列。</p>
 */
public interface BarrelService {

    List<CustomerBarrelAsset> getAssets(Long customerId, Long stationId);

    List<BarrelRecord> listRecords(Long customerId, Long stationId);

    void handleBarrelException(Long customerId, Long stationId, Long productId, Integer type, Integer quantity, Long relatedOrderId, String note, Long operatorId);

    /**
     * 站长审批退桶申请。
     *
     * <p><b>状态机（DEF-7，不允许跳步）：</b>1 待处理 → 2 已确认收到空桶 → 3 已退押金，
     * 另可从 1 / 2 走到 4 驳回。直接从 1 跳 3 会被拒绝——否则会出现"桶没收到就把钱退了"。</p>
     *
     * @param id          退桶记录ID
     * @param status      2=确认收到空桶 3=已退押金 4=驳回
     * @param handleNote  处理备注
     * @param operatorId  站长ID
     */
    void handleBarrelReturn(Long id, Integer status, String handleNote, Long operatorId);

    /**
     * 退桶试算（只读，不落库）：按押金条批次 FIFO 算出「退 N 个桶能拿回多少钱」。
     *
     * <p>让顾客在申请前就看到金额与依据，避免柜台吵架。
     * 返回 {@code hasMigratedPrice=true} 表示这批单价是历史迁移时推断的、不是真实成交价，前端应提示复核。</p>
     */
    Map<String, Object> previewReturn(Long customerId, Long stationId, Long productId, Integer quantity);

    /**
     * 获取按水类型分组的桶资产摘要（用于首页展示）
     */
    List<Map<String, Object>> getBarrelSummaryByType(Long customerId, Long stationId);

    /**
     * 获取当前客户的桶资产站级汇总（用于首页/详情页顶部汇总）
     */
    Map<String, Object> getBarrelSummary(Long customerId, Long stationId);
}
