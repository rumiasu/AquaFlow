package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告实体类，对应数据库 notice 表。
 * <p>用于向客户/员工发布系统公告、水站通知、活动。</p>
 */
@Data
public class Notice {

    /** 公告ID，主键自增 */
    private Long id;

    /** 所属水站ID(NULL=系统公告) */
    private Long stationId;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 类型: 1 系统公告 2 水站通知 3 活动 */
    private Integer type;

    /** 状态: 0 下架 1 发布 */
    private Integer status;

    /** 发布者ID */
    private Long publisherId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /* ==================== 派生文案（只读，随序列化下发给前端） ====================
     * 与 PaymentRecord.getStatusText()、Orders.getStatusText() 同一处理方式。
     * [2026-09-18 补] 站长端公告列表原本在前端写 `status === 1 ? '已发布' : '草稿'`，
     * 属本仓禁止的"前端自带映射表"（文案口径一旦后端调整，前端不跟随且不报错）。
     * 文案的唯一真相源是 {@link com.example.aquaflow.constant.NoticeStatus#textOf(Integer)}。
     */

    /** 发布状态文案（0 下架 → 草稿 / 1 发布 → 已发布），真相源是 NoticeStatus */
    public String getStatusText() {
        return com.example.aquaflow.constant.NoticeStatus.textOf(status);
    }
}
