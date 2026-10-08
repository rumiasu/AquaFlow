package com.example.aquaflow.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Minimum own-account check result; no customer identifiers, personal records or deletion authorization. */
@Data
public class AccountClosureCheckVO {
    private boolean complete;
    private boolean clear;
    private String message;
    private Instant checkedAt;
    private final String notice="仅为注销前检查，不会注销账户";
    private final String policyNotice="账户注销及资料处理规则尚未实施，请先核实未结事项。";
    private List<StationSummary> stations=new ArrayList<>();
    private List<BlockingItem> blockingItems=new ArrayList<>();

    @Data public static class StationSummary {
        private Long stationId;
        private String stationName;
        private Integer stationStatus;
    }
    @Data public static class BlockingItem {
        private Long stationId;
        private String stationName;
        private String category;
        private String label;
        private long count;
        private BigDecimal quantity=BigDecimal.ZERO;
        private BigDecimal amount=BigDecimal.ZERO;
        private String entry;
        private String entryLabel;
    }
}
