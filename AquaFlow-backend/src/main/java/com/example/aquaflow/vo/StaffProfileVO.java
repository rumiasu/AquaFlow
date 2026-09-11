package com.example.aquaflow.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 员工画像（站长视角）。
 * <p>在员工档案基础之上，聚合该员工的配送业绩（今日/本月/累计、进行中、完成率）
 * 与服务质量（异常、退回），并派生绩效等级等展示字段。前端只渲染不计算。</p>
 */
@Data
public class StaffProfileVO {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== 基础档案 ====================
    private Long id;
    private String name;
    private String phone;
    /** DELIVERY / STATION_MANAGER */
    private String role;
    private Long stationId;
    private String stationName;
    /** 1 在职 0 停用 */
    private Integer status;
    private LocalDateTime createTime;

    // ==================== 业绩画像 ====================
    /** 今日完成 */
    private Integer todayOrders;
    /** 本月完成 */
    private Integer monthOrders;
    /** 累计完成 */
    private Integer totalOrders;
    /**
     * 进行中 = 待配送(1) + 配送中(2)，即"还没干完的单"。
     * 术语：旧称"在途"，因与订单状态"配送中"撞名、且不含"待配送"易被误读，已统一改为"进行中"。
     */
    private Integer deliveringOrders;
    /** 已取消 */
    private Integer cancelledOrders;
    /** 累计配送金额 */
    private BigDecimal totalAmount;
    /** 本月配送金额 */
    private BigDecimal monthAmount;

    // ==================== 服务质量 ====================
    /** 桶异常次数 */
    private Integer exceptionCount;
    /** 退回站长次数 */
    private Integer returnCount;

    /** 进行中订单: {id, customerName, addressSnapshot, statusText, totalAmount} */
    private List<Map<String, Object>> currentOrders;

    // ==================== 派生展示字段 ====================

    public String getAvatarText() {
        if (name == null || name.isEmpty()) return "员";
        return name.substring(0, 1);
    }

    public String getRoleText() {
        if (role == null) return "员工";
        switch (role) {
            case "STATION_MANAGER": return "站长";
            case "DELIVERY": return "配送员";
            default: return role;
        }
    }

    public String getStatusText() {
        return status != null && status == 1 ? "在职" : "停用";
    }

    /** 入职天数 */
    public Integer getWorkDays() {
        if (createTime == null) return 0;
        return (int) ChronoUnit.DAYS.between(createTime, LocalDateTime.now());
    }

    public String getCreateTimeText() {
        return createTime == null ? "" : createTime.format(DAY);
    }

    /** 完成率（百分比，0-100） */
    public Integer getCompletionRate() {
        int done = totalOrders == null ? 0 : totalOrders;
        int cancel = cancelledOrders == null ? 0 : cancelledOrders;
        int total = done + cancel;
        if (total == 0) return 100;
        return (int) Math.round(done * 100.0 / total);
    }

    /** 绩效等级：按本月完成量分档 */
    public String getPerformanceLevel() {
        int m = monthOrders == null ? 0 : monthOrders;
        if (m >= 60) return "优秀";
        if (m >= 30) return "良好";
        if (m >= 10) return "一般";
        return "待提升";
    }

    public String getPerformanceColor() {
        switch (getPerformanceLevel()) {
            case "优秀": return "#67C23A";
            case "良好": return "#409EFF";
            case "一般": return "#E6A23C";
            default: return "#909399";
        }
    }

    /** 是否有进行中订单 */
    public Boolean getHasCurrentOrders() {
        return currentOrders != null && !currentOrders.isEmpty();
    }
}
