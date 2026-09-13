package com.example.aquaflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 配送端订单状态变更写请求的强类型载体集合（Phase D-C2）。
 *
 * <p>原来这些端点在 {@code DeliveryController} 内用 {@code Map<String,Object>} 接参并强转，
 * 缺字段/类型错时静默 NPE 或记错账。现统一改为强类型 + {@code jakarta.validation}，
 * 非法入参在边界被 {@code MethodArgumentNotValidException} → {@code Result.error} 拒回。</p>
 *
 * <p>字段约束严格对齐改造前 Controller 的 null 校验与 miniapp-delivery 实际发送的 JSON：
 * 必填项（dispatch 的 targetStationId、transfer/assign/claim-pool 的 deliveryStaffId、resolve 的 reason）
 * 用 {@code @NotNull}；可选项保持原默认语义（如缺省 reason 回退到各端点既定文案）。</p>
 */
public final class DeliveryOrderActionDTO {

    private DeliveryOrderActionDTO() {}

    /** rejectOrder：站长/配送员拒单，reason 可选（后端默认「水站拒单」） */
    @Data
    public static class Reject {
        private String reason;
    }

    /** resolveOrder：拒单并取消订单触发退款，reason 必填 */
    @Data
    public static class Resolve {
        @NotNull(message = "拒单原因必填")
        private String reason;
    }

    /** dispatchOrder：外派到指定水站 */
    @Data
    public static class Dispatch {
        @NotNull(message = "targetStationId 不能为空")
        private Long targetStationId;
        private String reason;
    }

    /** transferOrder：转给本站同事 */
    @Data
    public static class Transfer {
        @NotNull(message = "请指定接收配送员")
        private Long deliveryStaffId;
        private String reason;
    }

    /** returnToStation：配送员退回站长，reason 可选 */
    @Data
    public static class ReturnToStation {
        private String reason;
    }

    /** assignOrder：站长分配配送员，deliveryStaffId 必填 */
    @Data
    public static class Assign {
        @NotNull(message = "请指定配送员")
        private Long deliveryStaffId;
    }

    /** outsourceOrder：站长指定外派，targetStationId 可空（空则入抢单池） */
    @Data
    public static class Outsource {
        private Long targetStationId;
        private String reason;
    }

    /** claimPoolOrder：从抢单池抢单，deliveryStaffId 必填 */
    @Data
    public static class ClaimPool {
        @NotNull(message = "请指定配送员")
        private Long deliveryStaffId;
    }

    /** stationReject：站长拒单，reason 可选；tryDispatch 布尔（是否尝试外派入池） */
    @Data
    public static class StationReject {
        private String reason;
        private Boolean tryDispatch;
    }

    /**
     * reportOrder：配送员上报「非桶账类」配送异常（客户不接电话 / 地址找不到 / 客户拒收 / 水桶破损 / 其他）。
     * <p>它与「回桶差异」是两条线：回桶差异走 {@code order_barrel_exception} 与站长补偿流程，
     * 这里只做上报留痕 + 通知站长，不改订单状态、不动任何账。</p>
     */
    @Data
    public static class Report {
        @NotNull(message = "异常原因必填")
        private String reason;
    }

    // ==================== complete（回桶明细，Map 直传 service，需结构化） ====================

    /** complete 单条回桶明细：后端按 orderItemId 反查商品，客户端只给数量与原因 */
    @Data
    public static class CompleteItemReturn {
        @NotNull(message = "orderItemId 不能为空")
        private Long orderItemId;
        @NotNull(message = "实际回收桶数必填")
        private Integer actual;
        private String productName;
        private Integer expected;
        @Valid
        private List<CompleteItemReturnReason> reasons;
    }

    /** complete 回桶异常原因明细（key 见 OrderWorkflowServiceImpl 的 switch 映射） */
    @Data
    public static class CompleteItemReturnReason {
        private String key;
        private Integer qty;
    }

    /** completeOrder：回桶明细（可空，首桶订单免回桶核对）；兼容旧字段 returnBucketQty / barrelDiscrepancyNote */
    @Data
    public static class Complete {
        @Valid
        private List<CompleteItemReturn> itemReturns;
        private Integer returnBucketQty;
        private String barrelDiscrepancyNote;
    }
}
