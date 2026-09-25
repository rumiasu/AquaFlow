package com.example.aquaflow.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 客户画像（站长视角）。
 * <p>在客户档案基础之上，聚合该客户在本站的消费、资产、履约与行为数据，
 * 并派生等级/活跃度/头像等展示字段。前端只负责渲染，不承载任何业务计算。</p>
 */
@Data
public class CustomerProfileVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== 基础档案 ====================
    /** 客户ID */
    private Long id;
    private String name;
    private String phone;
    /** 1 个人 2 企业 */
    private Integer customerType;
    private String note;
    /** 逗号分隔标签 */
    private String tags;
    private LocalDateTime createTime;
    private LocalDateTime firstOrderTime;
    private LocalDateTime lastOrderTime;
    /** 常用地址（默认地址，省市区+详情） */
    private String defaultAddress;

    // ==================== 消费画像 ====================
    /** 累计完成订单 */
    private Integer totalOrders;
    /** 累计消费金额 */
    private BigDecimal totalConsumption;
    /** 本月订单数 */
    private Integer monthOrders;
    /** 本月消费金额 */
    private BigDecimal monthConsumption;
    /** 平均下单周期（天，来自档案） */
    private Integer avgCycleDays;

    // ==================== 资产 ====================
    /** 押金余额 */
    private BigDecimal depositBalance;
    /** 水票余额（张） */
    private Integer ticketBalance;
    /** 欠桶数 */
    private Integer owedBarrels;

    // ==================== 行为 ====================
    /** 常用商品 TOP3: {name, qty} */
    private List<Map<String, Object>> favoriteProducts;
    /** 最近订单: {id, status, statusText, totalAmount, createTime, createTimeText} */
    private List<Map<String, Object>> recentOrders;
    /** 桶异常次数 */
    private Integer exceptionCount;

    // ==================== 权限 ====================
    /** 是否开通本站货到付款 */
    private Boolean codEnabled;
    private Integer offlinePaymentEnabled;

    // ==================== 派生展示字段 ====================

    /** 头像文字（姓名首字） */
    public String getAvatarText() {
        if (name == null || name.isEmpty()) return "客";
        return name.substring(0, 1);
    }

    /** 客户类型文案 */
    public String getCustomerTypeText() {
        return customerType != null && customerType == 2 ? "企业客户" : "个人客户";
    }

    /** 客单价 */
    public BigDecimal getAvgOrderAmount() {
        if (totalConsumption == null || totalOrders == null || totalOrders == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return totalConsumption.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);
    }

    /** 客户等级：按累计消费分档 */
    public String getCustomerLevel() {
        BigDecimal c = totalConsumption == null ? BigDecimal.ZERO : totalConsumption;
        if (c.compareTo(BigDecimal.ZERO) <= 0) return "新客";
        if (c.compareTo(new BigDecimal("500")) < 0) return "普通客户";
        if (c.compareTo(new BigDecimal("2000")) < 0) return "银牌客户";
        return "金牌客户";
    }

    /** 等级配色（供前端徽标用） */
    public String getCustomerLevelColor() {
        switch (getCustomerLevel()) {
            case "金牌客户": return "#E6A23C";
            case "银牌客户": return "#909399";
            case "普通客户": return "#409EFF";
            default: return "#C0C4CC";
        }
    }

    /** 活跃度：活跃 / 沉默 / 流失 / 未下单 */
    public String getActivityStatus() {
        if (lastOrderTime == null) return "NONE";
        long days = ChronoUnit.DAYS.between(lastOrderTime, LocalDateTime.now());
        if (days <= 7) return "ACTIVE";
        if (days <= 30) return "SILENT";
        return "LOST";
    }

    public String getActivityText() {
        switch (getActivityStatus()) {
            case "ACTIVE": return "活跃";
            case "SILENT": return "沉默";
            case "LOST": return "流失";
            default: return "未下单";
        }
    }

    /** 最近下单距今描述 */
    public String getLastOrderAgoText() {
        if (lastOrderTime == null) return "暂无订单";
        long days = ChronoUnit.DAYS.between(lastOrderTime, LocalDateTime.now());
        if (days <= 0) return "今天";
        if (days < 30) return days + " 天前";
        if (days < 365) return (days / 30) + " 个月前";
        return (days / 365) + " 年前";
    }

    /** 标签数组（便于 wxml 循环渲染） */
    public List<String> getTagsList() {
        if (tags == null || tags.trim().isEmpty()) return new ArrayList<>();
        return Arrays.asList(tags.split("[,，\\s]+"));
    }

    public String getCreateTimeText() {
        return createTime == null ? "" : createTime.format(DAY);
    }

    public String getFirstOrderTimeText() {
        return firstOrderTime == null ? "" : firstOrderTime.format(DAY);
    }

    public String getLastOrderTimeText() {
        return lastOrderTime == null ? "" : lastOrderTime.format(FMT);
    }

    /** 欠桶文案 */
    public String getOwedBarrelsText() {
        int n = owedBarrels == null ? 0 : owedBarrels;
        return n > 0 ? n + " 个" : "无欠桶";
    }
}
