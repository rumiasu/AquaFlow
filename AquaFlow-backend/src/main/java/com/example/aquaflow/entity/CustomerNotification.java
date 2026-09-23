package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 客户通知实体，对应 customer_notification 表。
 * <p>用于向客户推送订单状态变更提醒（拒单、临时外派等）。</p>
 */
@Data
public class CustomerNotification {

    /** 主键ID */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 通知类型: REJECTED=拒单取消, TEMP_DISPATCH=临时外派配送 */
    private String type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 关联订单ID */
    private Long relatedOrderId;

    /** 是否已读: 0=未读, 1=已读 */
    private Integer isRead;

    /** 创建时间 */
    private LocalDateTime createTime;
}
