package com.example.aquaflow.service;

import java.util.List;

/**
 * 通知服务接口（多渠道：微信订阅消息 / 模板消息 / 站内信 / 短信）。
 *
 * <p><b>⚠️ 当前是占位实现，尚未接入任何真实渠道 —— 调用它不会发出任何通知。</b>
 * {@code NotificationServiceImpl} 的 6 个方法都是把文案拼好、写一行 {@code log.info} 就返回；
 * 全仓目前也<b>没有活跃调用方</b>（唯一的调用点写在 {@code OrderBarrelExceptionServiceImpl} 里，
 * 是注释掉的）。</p>
 *
 * <p><b>因此请勿因为"服务里有这个方法"就在业务流程或界面上承诺"已通知站长 / 客户"</b>，
 * 那会变成假承诺。{@code DeliveryController#reportOrder} 就是正确示范：它不调用本服务，
 * 而是在注释里如实写明"现状：异常只落在 {@code orders.special_note} 与 {@code audit_log}；
 * 影响：站长不会主动收到提醒，需自己翻订单详情"。</p>
 *
 * <p><b>TODO（待补，尚未排期）</b>：接入微信订阅消息 / 模板消息后，在此补发真实通知；
 * 届时须同步更新 {@code DeliveryController#reportOrder} 与
 * {@code OrderBarrelExceptionServiceImpl} 里的说明与调用点。</p>
 */
public interface NotificationService {

    /**
     * 推送异常创建通知 - 发送给站长
     */
    void pushExceptionCreated(Long stationId, Long exceptionId, String orderId, String category, int discrepancy);

    /**
     * 推送异常审批通过通知 - 发送给站长/配送员
     */
    void pushExceptionApproved(Long stationId, Long exceptionId, String action);

    /**
     * 推送补偿执行完成通知 - 发送给客户
     */
    void pushCompensationExecuted(Long customerId, Long exceptionId, String action, String detail);

    /**
     * 推送站内缺水协商通知 - 发送给客户
     */
    void pushStationShortageNegotiation(Long customerId, Long orderId, String proposeNote);

    /**
     * 推送配送员异常录入提醒 - 发送给站长（实时）
     */
    void pushStaffRecordedException(Long stationId, Long exceptionId, String staffName, String category);

    /**
     * 批量推送（用于定时任务汇总）
     */
    void pushBatchSummary(Long stationId, String summary);
}