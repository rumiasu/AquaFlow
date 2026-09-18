package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 意见反馈提交请求体（Phase F-3：FeedbackController.submit 由 Map 强类型化）。
 */
@Data
public class FeedbackCreateDTO {

    private String category;

    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 1000, message = "反馈内容不能超过1000字")
    private String content;

    private String contact;

    /**
     * 是否匿名提交（v46）。<b>可空，默认实名</b>：{@code null} / {@code false} 都是实名，
     * 只有显式传 {@code true} 才匿名。
     *
     * <p>用包装类型而不是 {@code boolean}，是为了让"没传"与"传了 false"都能被如实接收；
     * 落库前由 {@code FeedbackController} 归一成 {@code true/false}（列是
     * {@code NOT NULL DEFAULT 0}，不允许把 null 写进去）。</p>
     *
     * <p>匿名只对<b>站长端列表</b>生效（后端 SQL 层不返回身份）；客户自己的
     * {@code GET /api/feedback/my} 照常完整返回。前端不得据此自造身份文案。</p>
     */
    private Boolean anonymous;
}
