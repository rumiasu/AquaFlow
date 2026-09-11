package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.CustomerNotification;
import com.example.aquaflow.mapper.CustomerNotificationMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客户通知 API（客户小程序用）
 * <p>用于客户查询订单拒单/临时外派等提醒。</p>
 */
@RestController
@RequestMapping("/api/customer/notifications")
public class CustomerNotificationController {

    @Autowired
    private CustomerNotificationMapper notificationMapper;

    /** 获取通知列表（最近50条） */
    @GetMapping
    public Result<List<CustomerNotification>> list(@RequestParam(defaultValue = "50") int limit) {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(notificationMapper.listByCustomerId(customerId, limit));
    }

    /** 获取未读通知（用于弹窗提醒） */
    @GetMapping("/unread")
    public Result<List<CustomerNotification>> unread() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(notificationMapper.listUnreadByCustomerId(customerId));
    }

    /** 未读数量 */
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        Long customerId = AuthContext.requireCustomerId();
        return Result.success(notificationMapper.countUnread(customerId));
    }

    /** 标记单条已读 */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        Long customerId = AuthContext.requireCustomerId();
        // [AQ-035] 归属校验下沉到 SQL：旧实现查到通知后丢弃结果、直接按 id 更新，
        // 顾客传任意 id 即可把他人通知标记已读。此处只更新"属于自己"的通知，0 行即越权/不存在。
        int affected = notificationMapper.markReadOwned(id, customerId);
        if (affected <= 0) {
            return Result.error("通知不存在或无权操作");
        }
        return Result.success();
    }

    /** 全部标记已读 */
    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        Long customerId = AuthContext.requireCustomerId();
        notificationMapper.markAllRead(customerId);
        return Result.success();
    }
}
