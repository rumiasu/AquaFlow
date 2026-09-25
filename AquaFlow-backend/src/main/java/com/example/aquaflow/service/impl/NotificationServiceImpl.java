package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通知服务实现
 * 当前实现：控制台日志 + 预留微信订阅消息/模板消息接口
 * 生产环境需对接微信订阅消息/模板消息/企业微信/短信网关
 */
@Service
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    @Autowired
    private StaffMapper staffMapper;

    @Override
    public void pushExceptionCreated(Long stationId, Long exceptionId, String orderId, String category, int discrepancy) {
        // 获取站长列表
        List<Staff> managers = staffMapper.listByStationIdAndRole(stationId, "STATION_MANAGER");
        
        String categoryName = getCategoryName(category);
        String message = String.format(
            "【新异常提醒】\n订单：%s\n类型：%s\n差异：%s%d 桶\n异常ID：%d\n请及时处理",
            orderId, categoryName, discrepancy > 0 ? "少回 " : "多回 ", Math.abs(discrepancy), exceptionId
        );

        for (Staff m : managers) {
            // TODO: 发送微信订阅消息/模板消息
            // wxMpTemplateMessageService.send(m.getWxOpenId(), templateId, message);
            log.info("[Notification] 推送新异常给站长: staffId={}, exceptionId={}", m.getId(), exceptionId);
        }
    }

    @Override
    public void pushExceptionApproved(Long stationId, Long exceptionId, String action) {
        List<Staff> managers = staffMapper.listByStationIdAndRole(stationId, "STATION_MANAGER");
        
        String actionName = getActionName(action);
        String message = String.format("【异常审批通过】\n异常ID：%d\n处理动作：%s\n已开始执行补偿", exceptionId, actionName);

        for (Staff m : managers) {
            log.info("[Notification] 推送审批通过: staffId={}, exceptionId={}", m.getId(), exceptionId);
        }
    }

    @Override
    public void pushCompensationExecuted(Long customerId, Long exceptionId, String action, String detail) {
        // TODO: 通过客户openid发送微信订阅消息
        String actionName = getActionName(action);
        String message = String.format("【异常处理完成】\n您的订单异常已处理完毕\n处理方式：%s\n详情：%s\n如有疑问请联系水站", actionName, detail);
        
        log.info("[Notification] 推送补偿执行完成: customerId={}, exceptionId={}", customerId, exceptionId);
    }

    @Override
    public void pushStationShortageNegotiation(Long customerId, Long orderId, String proposeNote) {
        String message = String.format("【缺水协商】\n您的订单 #%d 因站内缺水无法按时配送\n站长建议：%s\n请确认是否接受调整", orderId, proposeNote);
        
        log.info("[Notification] 推送缺水协商: customerId={}, orderId={}", customerId, orderId);
    }

    @Override
    public void pushStaffRecordedException(Long stationId, Long exceptionId, String staffName, String category) {
        List<Staff> managers = staffMapper.listByStationIdAndRole(stationId, "STATION_MANAGER");
        
        String categoryName = getCategoryName(category);
        String message = String.format(
            "【配送员录入异常】\n配送员：%s\n异常类型：%s\n异常ID：%d\n请及时查看处理",
            staffName, categoryName, exceptionId
        );

        for (Staff m : managers) {
            log.info("[Notification] 推送配送员录入异常: staffId={}, exceptionId={}", m.getId(), exceptionId);
        }
    }

    @Override
    public void pushBatchSummary(Long stationId, String summary) {
        List<Staff> managers = staffMapper.listByStationIdAndRole(stationId, "STATION_MANAGER");
        
        String message = "【每日异常汇总】\n" + summary;
        
        for (Staff m : managers) {
            log.info("[Notification] 推送每日汇总: staffId={}", m.getId());
        }
    }

    /**
     * 异常类别中文文案 —— [2026-09-23] 原来这里有一份 switch，与
     * {@code entity/OrderBarrelException.getCategoryText()} <b>逐字相同</b>（同一套 7 个取值
     * 存在于两个 switch 里，加一个类别要改两处）。现统一委托
     * {@link com.example.aquaflow.constant.ExceptionCategory#textOf}。
     */
    private String getCategoryName(String category) {
        return com.example.aquaflow.constant.ExceptionCategory.textOf(category);
    }

    private String getActionName(String action) {
        switch (action) {
            case "REFUND_TICKET": return "退水票";
            case "REFUND_CASH": return "退现金";
            case "WAIVE_DEPOSIT": return "减免押金";
            case "ADJUST_ASSET": return "调整桶资产";
            case "RESCHEDULE": return "重新安排";
            case "IGNORE": return "忽略";
            case "ESCALATE": return "转人工";
            default: return action;
        }
    }
}