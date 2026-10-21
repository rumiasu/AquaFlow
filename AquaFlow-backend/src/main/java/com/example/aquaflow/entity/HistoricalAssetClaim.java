package com.example.aquaflow.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;
import java.time.LocalDateTime;

/** 历史申请快照及唯一终态；不附带平台客户档案。 */
@Data
public class HistoricalAssetClaim {
    private Long id;
    private Long customerId;
    private Long stationId;
    private String clientToken;
    @JsonIgnore private String requestDigest;
    @JsonRawValue private String requestJson;
    private String status;
    private Long reviewerId;
    private String reviewNote;
    private LocalDateTime createTime;
    private LocalDateTime reviewTime;
    public String getStatusText() {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待本站核对";
            case "APPROVED" -> "已生效";
            case "REJECTED" -> "已驳回";
            default -> "状态待核实";
        };
    }
}
