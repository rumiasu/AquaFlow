package com.example.aquaflow.service;

import java.util.List;

/**
 * 通知服务接口
 * 支持：微信订阅消息、模板消息、站内信、短信等多渠道
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