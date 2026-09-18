package com.example.aquaflow.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 客户 × 水站 视图对象。
 * <p>用于站长管理端"客户查询/详情"：在基础客户档案之上，join 出该客户在本站的
 * 权限配置（如货到付款 offlinePaymentEnabled）与统计信息，并派生后端展示字段，
 * 前端只负责渲染、不承载业务/展示逻辑。</p>
 */
@Data
public class CustomerStationVO {

    /** 客户ID */
    private Long id;

    /** 客户名 */
    private String name;

    /** 联系电话 */
    private String phone;

    /** 客户类型: 1 个人 2 企业 */
    private Integer customerType;

    /** 备注 */
    private String note;

    /** 押金余额 */
    private BigDecimal depositBalance;

    /** 累计订单数 */
    private Integer totalOrders;

    /** 累计消费金额 */
    private BigDecimal totalConsumption;

    /** 标签（逗号分隔） */
    private String tags;

    /** 首次下单时间 */
    private LocalDateTime firstOrderTime;

    /** 最近配送时间 */
    private LocalDateTime lastDeliveryTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 货到付款（线下支付）权限: 0 关闭 1 开启 */
    private Integer offlinePaymentEnabled;

    /**
     * 搜索命中的地址文本（默认地址优先）。
     *
     * <p>只在 {@code GET /api/customers?keyword=} 带关键字时回填：站长"更看重地址，
     * 地址其实更能指代人"（口径见 {@code util/CustomerSearchMatcher}），
     * 不显示"送到哪"的话，搜出来的结果看着像随机命中的。</p>
     *
     * <p>不带关键字的普通列表恒为 {@code null} —— 地址不在 {@code listStationCustomers}
     * 的返回列里，逐行补查地址会让"每个客户一次地址扫描"。</p>
     */
    private String addressText;

    /** 客户等级文本（后端派生：按累计消费/订单数分档，前端直接渲染） */
    private String customerLevel;

    /** 客户等级对应配色（后端派生，前端直接用作 badge 背景） */
    private String customerLevelColor;

    /** 活跃状态标识（后端派生：active/sleeping/lost/new，前端用作 badge class） */
    private String activityStatus;

    /** 活跃状态文本（后端派生：活跃/沉睡/流失/新客，前端直接渲染） */
    private String activityText;

    /** 客户类型文本（后端派生，前端直接渲染） */
    public String getCustomerTypeText() {
        return customerType != null && customerType == 2 ? "企业客户" : "个人客户";
    }

    /** 是否已开通货到付款（后端派生，前端直接渲染开关状态） */
    public Boolean getCodEnabled() {
        return offlinePaymentEnabled != null && offlinePaymentEnabled == 1;
    }

    /**
     * 后端统一派生「等级 / 活跃度」展示字段，前端只读不计算。
     * 等级：累计消费 ≥2000 或订单 ≥30 → VIP客户；≥500 或 ≥10 → 老客户；否则普通客户。
     * 活跃：以最近配送时间（无则首次下单时间）为基准，≤30 天活跃、≤90 天沉睡、否则流失；无时间则新客。
     */
    public void deriveProfileMeta() {
        BigDecimal cons = totalConsumption == null ? BigDecimal.ZERO : totalConsumption;
        int orders = totalOrders == null ? 0 : totalOrders;
        if (cons.compareTo(new BigDecimal("2000")) >= 0 || orders >= 30) {
            customerLevel = "VIP客户";
            customerLevelColor = "#E6A23C";
        } else if (cons.compareTo(new BigDecimal("500")) >= 0 || orders >= 10) {
            customerLevel = "老客户";
            customerLevelColor = "#409EFF";
        } else {
            customerLevel = "普通客户";
            customerLevelColor = "#909399";
        }

        LocalDateTime ref = lastDeliveryTime != null ? lastDeliveryTime : firstOrderTime;
        if (ref == null) {
            activityStatus = "new";
            activityText = "新客";
        } else {
            long days = java.time.Duration.between(ref, LocalDateTime.now()).toDays();
            if (days <= 30) {
                activityStatus = "active";
                activityText = "活跃";
            } else if (days <= 90) {
                activityStatus = "sleeping";
                activityText = "沉睡";
            } else {
                activityStatus = "lost";
                activityText = "流失";
            }
        }
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String getCreateTimeText() {
        return createTime == null ? "" : createTime.format(FMT);
    }

    public String getFirstOrderTimeText() {
        return firstOrderTime == null ? "暂无" : firstOrderTime.format(FMT);
    }

    public String getLastDeliveryTimeText() {
        return lastDeliveryTime == null ? "暂无" : lastDeliveryTime.format(FMT);
    }
}
