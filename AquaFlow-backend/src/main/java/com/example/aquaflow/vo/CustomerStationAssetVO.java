package com.example.aquaflow.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户在「本站」的资产视图（站长视角）。
 *
 * <p><b>口径约定（重要）：</b>客户是全局身份，但水桶 / 水票 / 押金三类资产严格按
 * {@code (customer_id, station_id)} 隔离。本 VO 的全部数据都只能来自
 * customerId + 登录站长所属 stationId 这一对组合，任何一条 SQL 都不允许省略 station_id，
 * 否则会把客户在其他水站的资产串到本站来展示。</p>
 *
 * <p>所有中文文案（类型、状态、正负号）均由后端派生下发，前端只渲染字段。</p>
 */
@Data
public class CustomerStationAssetVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ==================== 定位信息 ====================
    private Long customerId;
    private String customerName;
    private String phone;
    /** 本次查询所限定的水站（取自登录站长，不接受前端传入） */
    private Long stationId;
    private String stationName;

    // ==================== 资产概览 ====================
    /** 持有桶：客户手上实际持有的空桶/在用桶 */
    private Integer heldBuckets;
    /** 配送中桶：配送中尚未交付确认的桶 */
    private Integer inTransitBuckets;
    /** 欠桶：客户应还未还 */
    private Integer owedBuckets;
    /** 持有桶对应的押金合计（按各商品当前押金单价 × 持有数） */
    private BigDecimal barrelDepositTotal;
    /** 押金账户余额（唯一真值：customer_deposit_account.balance） */
    private BigDecimal depositBalance;
    /** 水票总张数 */
    private Integer ticketQuantity;
    /** 水票折算金额（按站级水票价 × 剩余张数） */
    private BigDecimal ticketValue;
    /** 资产合计 = 押金账户余额 + 水票折算金额 */
    private BigDecimal totalAssetValue;

    // ==================== 明细 ====================
    /** 水桶明细（按商品） */
    private List<BarrelItem> barrels = new ArrayList<>();
    /** 水票明细（按商品） */
    private List<TicketItem> tickets = new ArrayList<>();
    /** 资产变动流水（桶 / 水票 / 押金三源合并，按时间倒序） */
    private List<AssetRecord> records = new ArrayList<>();
    /** 流水是否被截断（只返回最近 N 条） */
    private Boolean recordsTruncated;
    /** 流水返回条数上限 */
    private Integer recordsLimit;

    // ==================== 派生展示文案 ====================

    public String getHeldBucketsText() {
        return nz(heldBuckets) + " 个";
    }

    public String getInTransitBucketsText() {
        return nz(inTransitBuckets) + " 个";
    }

    public String getOwedBucketsText() {
        int n = nz(owedBuckets);
        return n > 0 ? n + " 个" : "无欠桶";
    }

    public Boolean getHasOwed() {
        return nz(owedBuckets) > 0;
    }

    public String getTicketQuantityText() {
        return nz(ticketQuantity) + " 张";
    }

    /** 是否完全没有资产（前端据此显示空状态） */
    public Boolean getEmpty() {
        return nz(heldBuckets) == 0 && nz(inTransitBuckets) == 0 && nz(owedBuckets) == 0
                && nz(ticketQuantity) == 0 && money(depositBalance).compareTo(BigDecimal.ZERO) == 0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ==================== 内部类型 ====================

    /** 水桶明细行（按商品聚合） */
    @Data
    public static class BarrelItem {
        private Long productId;
        private String productName;
        private String productSpec;
        /**
         * 持有数量 = **权益 + 配送中**（展示口径）：客户视角"这个客户一共有几个桶，买了就是他的"。
         * <p>[2026-09-15] 与 {@link #rightQty}（已到手）拆开，避免同一字段既当"展示持有"又当
         * "可用于下单抵扣/退押金"的基准 —— 两者混用会让页面与账务互相打架。</p>
         */
        private Integer heldQty;
        /**
         * 权益数（**已到手**、可退的桶）。下单抵扣与退押金只认这个数；
         * {@link #heldQty} 减去它 = 在途（配送中）部分。
         */
        private Integer rightQty;
        /** 配送中数量（只含 PENDING；DELIVERED = 已送达，不算在途） */
        private Integer inTransitQty;
        /**
         * over（<b>可为负</b>）：正数 = 欠桶，负数 = 顾客多还的桶寄存在水站。
         * <p>合法状态，不是脏数据、不是负债、不是负权益。前端必须如实显示，
         * 不能当成 0 ——那是顾客打电话问"我的桶呢"的直接来源。</p>
         */
        private Integer overQty;
        /** 占用 = 权益 + over：顾客手上实际有几个桶（派生，**不含配送中**）。纯还桶的数量上限就是它。 */
        private Integer occupiedQty;
        /** 单桶押金 */
        private BigDecimal depositPerBucket;
        /** 该商品持有桶对应押金 = 单桶押金 × 持有数 */
        private BigDecimal depositAmount;

        /** 可还桶上限：占用数，且不为负（前端还桶表单直接用它） */
        public Integer getMaxReturnableQty() {
            return Math.max(0, occupiedQty == null ? 0 : occupiedQty);
        }

        /** over 文案：欠桶 / 暂存，0 时返回空串 */
        public String getOverText() {
            int n = overQty == null ? 0 : overQty;
            if (n > 0) return "欠桶 " + n + " 个";
            if (n < 0) return "暂存 " + (-n) + " 个";
            return "";
        }

        /** 数量描述：只拼接真正非 0 的部分，避免出现"持有 0 个，配送中 4 个"这种别扭文案 */
        public String getQuantityText() {
            StringBuilder sb = new StringBuilder();
            if (nz(heldQty) > 0) {
                sb.append("持有 ").append(heldQty).append(" 个");
            }
            if (nz(inTransitQty) > 0) {
                if (sb.length() > 0) sb.append("，");
                sb.append("配送中 ").append(inTransitQty).append(" 个");
            }
            if (nz(overQty) < 0) {
                if (sb.length() > 0) sb.append("，");
                // 多还的桶：明确写"寄存在水站"，别让顾客以为桶丢了
                sb.append("水站暂存 ").append(-nz(overQty)).append(" 个");
            }
            return sb.length() == 0 ? "无" : sb.toString();
        }
    }

    /** 水票明细行（按商品聚合） */
    @Data
    public static class TicketItem {
        private Long productId;
        private String productName;
        private String productSpec;
        /** 剩余张数 */
        private Integer remainQuantity;
        /** 站级水票单价 */
        private BigDecimal unitPrice;
        /** 折算金额 = 单价 × 剩余张数 */
        private BigDecimal totalValue;
        /** 最近一次变动时间 */
        private LocalDateTime updateTime;

        public String getRemainText() {
            return nz(remainQuantity) + " 张";
        }

        public String getUpdateTimeText() {
            return updateTime == null ? "" : updateTime.format(FMT);
        }

        /** 单价缺失时单价按 0 显示，避免前端出现 NaN */
        public BigDecimal getUnitPriceSafe() {
            return unitPrice == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : unitPrice;
        }
    }

    /** 资产变动流水（桶 / 水票 / 押金三源合并） */
    @Data
    public static class AssetRecord {
        /** BARREL 水桶 / TICKET 水票 / DEPOSIT 押金 */
        private String category;
        /** 归属模块中文名：水桶 / 水票 / 押金 */
        private String categoryText;
        /** 业务类型中文文案，如「退桶」「新增押金桶」「押金入账」 */
        private String typeText;
        /** 该笔的增减文案，如「+2 个」「-1 张」「+¥60.00」；无法量化时为空 */
        private String changeText;
        /** 增减方向：IN 增加 / OUT 减少 / FLAT 不变，前端据此配色 */
        private String direction;
        /** 状态文案（仅退桶申请有意义） */
        private String statusText;
        /** 商品名（可能为空） */
        private String productName;
        /** 关联订单号（可能为空） */
        private Long orderId;
        /** 备注 / 来源 */
        private String note;
        /** 发生时间 */
        private LocalDateTime time;
        private String timeText;
    }
}
