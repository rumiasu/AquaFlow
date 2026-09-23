package com.example.aquaflow.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 员工-水站绑定操作请求体（Phase F-2：DeliveryBindingController 6 端点由 Map 强类型化）。
 * <p>applicationId 与 staffId 二选一（后端优先 applicationId，都不传走"找不到待审批申请"业务拒绝），
 * 因此不做 @NotNull 硬校验，保持原有兼容语义。</p>
 */
public class BindingActionDTO {

    /** POST /api/delivery/bind/apply：配送员申请绑定水站 */
    @Data
    public static class ApplyBind {
        @NotNull(message = "stationId 不能为空")
        private Long stationId;

        private String applyNote;
    }

    /**
     * 审批类端点共用：approve/reject 绑定、unbind-confirm/unbind-reject 解绑。
     * approve/unbind-confirm 读 handleNote；reject/unbind-reject 读 reason。
     */
    @Data
    public static class Handle {
        private Long applicationId;

        private Long staffId;

        private String handleNote;

        private String reason;
    }

    /** POST /api/manager/bind/release：站长强制解除配送员绑定 */
    @Data
    public static class Release {
        @NotNull(message = "staffId 不能为空")
        private Long staffId;

        private String reason;
    }
}
