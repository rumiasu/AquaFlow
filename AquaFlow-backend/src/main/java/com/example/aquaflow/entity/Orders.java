package com.example.aquaflow.entity;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体类，对应数据库 orders 表。
 * <p>这是整个系统核心。</p>
 */
@Data
public class Orders {

    /** 订单ID，主键自增 */
    private Long id;

    /** 客户ID */
    private Long customerId;

    /** 客户名称(关联查询字段) */
    private String customerName;

    /** 客户电话(关联查询字段) */
    private String customerPhone;

    /** 地址ID */
    private Long addressId;

    /** 地址详情(关联查询字段) */
    private String addressDetail;

    /** 订单归属水站 */
    private Long stationId;

    /** 实际履约配送水站ID，可与 station_id 不同 */
    private Long deliveryStationId;

    /** 配送员ID */
    private Long deliveryStaffId;

    /** 配送员姓名(关联查询字段) */
    private String deliveryStaffName;

    /** 订单总数量（所有商品数量之和） */
    private Integer quantity;

    /** 来源: 1 电话 2 微信 3 小程序 */
    private Integer source;

    /** 订单状态（canonical 1-5）：1 待配送 2 配送中 3 已送达 4 已完成 5 已取消（是否分配用 delivery_staff_id 判断） */
    private Integer status;

    /** 支付方式: 1 微信支付 2 现金(货到付款) 3 水票（水票视同已付） */
    private Integer paymentMethod;

    /** 支付状态: 0 未支付 1 待收款 2 已付款 3 已退款 4 已取消 */
    private Integer paymentStatus;

    // ============ 支付展示态：由后端统一计算并输出（非数据库列，仅序列化给前端）============
    // 背景：历史上用户端/配送端各自维护一套 paymentStatus -> 文案的映射，配送端还误读了
    // 并不存在的 collected 字段，导致同一单两端显示不一致（用户端"已付款"、配送端"待收款"）。
    // 约定：支付语义只能由后端判定，前端一律直接渲染 payState / payStateText / needCollect，
    // 禁止再各自写映射表或读 collected，从结构上杜绝漂移。

    /** 支付态编码：UNPAID / PENDING / PAID / REFUNDED / CANCELLED */
    public String getPayState() {
        if (paymentStatus == null) return "UNPAID";
        switch (paymentStatus) {
            case PaymentStatus.PAID:      return "PAID";
            case PaymentStatus.REFUNDED:  return "REFUNDED";
            case PaymentStatus.CANCELLED: return "CANCELLED";
            case PaymentStatus.PENDING:   return "PENDING";
            default:                      return "UNPAID";
        }
    }

    /** 支付状态中文文案（全系统唯一文案来源） */
    public String getPayStateText() {
        switch (getPayState()) {
            case "PAID":      return "已付款";
            case "REFUNDED":  return "已退款";
            case "CANCELLED": return "已取消";
            case "PENDING":   return "待收款";
            default:          return "未付款";
        }
    }

    /**
     * 是否仍需配送员现场收款（货到付款）：仅「现金支付(2) 且 尚未收款(非 PAID)」为 true。
     * 微信(1)/水票(3)/已付(2)/已退款(3)/已取消支付(4) 一律不需要现场收款。
     * 旧实现把「微信未付」「已取消支付」也判为需收款，与真实业务（微信用户线上下单、不现场收）不符。
     */
    public Boolean getNeedCollect() {
        return paymentMethod != null
                && paymentMethod == 2
                && paymentStatus != PaymentStatus.PAID;
    }

    // ============ 订单状态 / 支付方式文案：由后端统一计算并输出 ============
    // 同一套 state -> 文案的映射曾在用户端 OrderCard、用户端订单详情、配送端订单详情、
    // 配送端 station-mgmt/orders 各写一份，改叫法或加状态时必然漂移。
    // 约定：前端一律直接渲染 statusText / payMethodText，禁止自行映射。

    /** 订单状态中文文案（全系统唯一来源） */
    public String getStatusText() {
        return OrderStatus.textOf(status);
    }

    /** 支付方式中文文案（全系统唯一来源） */
    public String getPayMethodText() {
        return PayMethod.textOf(paymentMethod);
    }

    // ============ 可执行操作：业务规则由后端判定，前端不再自行推导 ============

    /** 是否允许取消（待配送/配送中/已送达 允许；已完成、已取消不允许） */
    public Boolean getCanCancel() {
        return OrderStatus.isCancellable(status);
    }

    /** 是否展示支付入口：订单未取消 且 支付态为 未付款/待收款/支付已取消 */
    public Boolean getCanRepay() {
        if (status != null && status == OrderStatus.CANCELLED) return false;
        String s = getPayState();
        return "UNPAID".equals(s) || "PENDING".equals(s) || "CANCELLED".equals(s);
    }

    /** 支付入口按钮文案：支付被取消过显示为「重新支付」，否则「去支付」 */
    public String getRepayLabel() {
        return "CANCELLED".equals(getPayState()) ? "重新支付" : "去支付";
    }

    // ============ 转单（退回/转让）状态：由后端按 special_note 标记统一判定 ============
    // 背景：配送端订单详情曾读取 transferStatus / returnStatus / isTransferTarget 三个
    // 后端根本不存在的字段（与 collected 同类问题），导致「转单中/退回申请/待你确认」
    // 标签永远不显示。改为后端按真实标记计算后下发。

    /** 转单类型：NONE 无 / STAFF 配送员转单 / DIRECTED 站间指定外派退回 */
    public String getTransferKind() {
        String note = specialNote == null ? "" : specialNote;
        if (note.contains("[指定退回待确认]")) return "DIRECTED";
        // 配送员转单：[退回站长]/[转让]/[重分配] 经 同意/拒绝 后会改写为
        // "[退回站长-已同意]"/"[退回站长-已拒绝]"/"[转让-已同意]" 等，必须排除已解决标记，
        // 否则已处理完的转单会被持续误判为「转单中」。
        boolean staffActive =
                (note.contains("[退回站长]")
                        && !note.contains("[退回站长-已同意]") && !note.contains("[退回站长-已拒绝]"))
                || (note.contains("[转让]")
                        && !note.contains("[转让-已同意]") && !note.contains("[转让-已拒绝]"))
                || (note.contains("[重分配]")
                        && !note.contains("[重分配-已同意]") && !note.contains("[重分配-已拒绝]"));
        if (staffActive) return "STAFF";
        return "NONE";
    }

    /** 是否处于转单中（需站长决策 同意/拒绝，不显示 分配配送员/外派） */
    public Boolean getTransferPending() {
        return !"NONE".equals(getTransferKind());
    }

    /** 转单状态中文文案（无转单时为空串） */
    public String getTransferText() {
        switch (getTransferKind()) {
            case "DIRECTED": return "转单待确认";
            case "STAFF":    return "转单中";
            default:         return "";
        }
    }

    /** 结算状态: 1 未结算 2 已结算 */
    private Integer settlementStatus;

    /** 应结算日期 */
    private java.time.LocalDate dueDate;

    /** 批次ID */
    private Long batchId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 水费金额 */
    private BigDecimal waterAmount;

    /**
     * 水费金额（不含押金）。
     * <p>历史订单可能未回填 water_amount，此前由前端按 totalAmount - depositAmount 兜底计算，
     * 属业务计算散落前端。改由后端统一兜底，前端直接取用。</p>
     */
    public BigDecimal getWaterAmount() {
        if (waterAmount != null) return waterAmount;
        if (totalAmount == null) return null;
        BigDecimal deposit = depositAmount != null ? depositAmount : BigDecimal.ZERO;
        BigDecimal water = totalAmount.subtract(deposit);
        return water.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : water;
    }

    /** 押金金额 */
    private BigDecimal depositAmount;

    /** 收件人姓名 */
    private String receiverName;

    /** 收件人电话 */
    private String receiverPhone;

    /** 地址快照 */
    private String addressSnapshot;

    /** 地址快照纬度 */
    private BigDecimal addressSnapshotLat;

    /** 地址快照经度 */
    private BigDecimal addressSnapshotLng;

    /** 门卫信息 */
    private String guardInfo;

    /** 配送时间要求 */
    private String deliveryTimeRequest;

    /** 特殊说明 */
    private String specialNote;

    /** 配送桶数量 */
    private Integer deliveryBucketQty;

    /** 回收桶数量 */
    private Integer returnBucketQty;

    /** 差桶数量 */
    private Integer barrelDiscrepancy;

    /** 差桶差异说明 */
    private String barrelDiscrepancyNote;

    /** 是否有异常标记 */
    private Boolean exceptionFlag;

    /** 是否首次桶装水订单（押金桶无需回桶） */
    private Boolean firstBarrelOrder;

    /** 首个异常类别 */
    private String exceptionCategory;

    /** 异常次数 */
    private Integer exceptionCount;

    /** 关联的桶异常记录ID */
    private Long barrelExceptionId;

    /** 幂等键：防止重复下单 */
    private String idempotencyKey;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 首个商品名称(列表联查字段) */
    private String firstProductName;

    /**
     * 是否「转给我、待我确认」（瞬时字段，非数据库列）。
     * <p>由后端按当前登录人 + 订单转单标记判定后填充，前端详情页据此决定显示
     * 「同意并接单/拒绝转单」还是常规操作栏。此前前端读取并不存在的 isTransferTarget，
     * 该判断恒为 false，转单确认入口从未出现。</p>
     */
    private transient Boolean transferTarget;

    /** 订单商品明细(关联查询字段) */
    private List<OrderItem> items;
}
