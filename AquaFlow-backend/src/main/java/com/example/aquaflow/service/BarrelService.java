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

    /**
     * 站长端「欠桶台账」：本站**当前仍欠桶**的客户，按欠得最久排前面。
     *
     * <p>口径：{@code overQty} 是 {@code customer_barrel_over.over_qty} 的当前净额（只取 &gt;0），
     * 「欠了几天」来自 v29 新增的 {@code owed_since}。明细（哪一单欠的、差几个、处理到哪一步）
     * 走现成的异常单 {@code order_barrel_exception}（{@code discrepancy > 0}），两者不可相加。</p>
     *
     * <p><b>本方法只读、不做任何风控。</b>欠桶已改为「只提醒不阻断」：下单是否放行与欠桶无关，
     * 提醒由 {@code OrderServiceImpl.buildOwedWarnings} 写进下单响应的 warnings
     * （原 [AQ-030] 的「欠桶 ≥5 拒绝下单」硬拦已于 2026-09-15 按产品决定移除）。</p>
     *
     * @param minDays 只返回欠桶天数 ≥ 该值的行；null / ≤0 表示不过滤。
     *                天数未知（历史存量未回填 {@code owed_since}）的行**一并保留**：宁可多提醒，不可漏催收。
     */
    List<com.example.aquaflow.vo.OwedBarrelVO> listOwedCustomers(Long stationId, Integer minDays);
}
