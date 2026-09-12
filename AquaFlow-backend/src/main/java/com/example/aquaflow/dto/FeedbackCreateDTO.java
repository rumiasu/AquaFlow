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
}
