package com.example.aquaflow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分级告警记录（{@code alert_log}）。
 *
 * <p>为什么不像原来那样只 {@code log.info} 就完事：日志是"谁去翻才有"，
 * 而告警要回答的是"**谁该处理、处理没有**"。所以每条告警都落库，带上
 * {@link com.example.aquaflow.constant.AlertType}（决定收件人）与投递状态。</p>
 *
 * <p>投递方向与收件人语义见 {@code constant/AlertType.java}。</p>
 */
@Data
public class AlertLog {

    private Long id;

    /** {@code AlertType.SYSTEM} / {@code AlertType.OPERATION}，决定收件人 */
    private String alertType;

    /** ERROR / WARN / INFO */
    private String level;

    /** 产生位置（类/环节名），排查时用来定位 */
    private String source;

    /** 运营告警的收件水站；系统告警恒为 NULL */
    private Long stationId;

    /** 运营告警的收件站长；当时没有站长则为 NULL（只落库不丢） */
    private Long staffId;

    private String title;

    private String content;

    /** 关联业务对象类型，如 ORDER_BARREL_EXCEPTION */
    private String relatedType;

    /** 关联业务对象 id */
    private Long relatedId;

    /** LOGGED=只落库+日志（外部渠道未配置）/ PUSHED=已推送外部渠道 / FAILED=推送失败 */
    private String notifyStatus;

    private LocalDateTime createTime;
}
