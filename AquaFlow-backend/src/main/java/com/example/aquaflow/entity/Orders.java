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

    // =========================================================================
    // 客户信用标记（**关联/计算字段，不是 orders 表的列**）—— 2026-09-21 新增
    //
    // 用途：站长端的订单列表据此**上色**（黄=有挂账 / 红=逾期或超额度），
    // 让他一眼看出"这单的客户欠着钱"。与上面的 customerName / addressDetail 同类：
    // 由列表查询或控制器填充，**没有对应数据库列**，写库时会被忽略。
    //
    // ⚠️ 别把它们当成持久化字段：`orderMapper.save/update` 的 SQL 是显式列名，写不进去；
    //    MyBatis 的 `select o.*` 也不会填它们（值为 null = 该行没做过信用标记）。
    // =========================================================================

    /** 客户类型：1 个人 / 2 企业（关联查询字段，见 constant 与 customer 表注释） */
    private Integer customerType;

    /** 客户在本站的信用等级：NORMAL / WATCH / ALERT / FREEZE（见 CustomerRiskService） */
    private String customerRiskLevel;

    /**
     * 上面那个等级的中文（正常 / 关注 / 预警 / 冻结）—— <b>文案只有一个来源</b>
     * （{@code CustomerRiskService.textOf}），列表徽标直接用，前端不许自带 NORMAL→「正常」映射表。
     */
    private String customerRiskLevelText;

    /**
     * 给站长看的一句话（"有 ¥320.00 挂账，都在账期内" / "有 2 笔逾期未结（最长 9 天）：不再给新的赊账单"）。
     *
     * <p>同样由后端拼好下发，前端**不自己拼**"欠了多少 / 逾期几天" —— 否则同一个客户
     * 在列表上与在详情页上会变成两句不一样的话。等级为 {@code NORMAL} 时也下发（"没有未结欠款"），
     * 前端据此判断"要不要标出来"是它自己的展示决定，不用猜字段缺失。</p>
     */
    private String customerRiskNote;

    /** 未结赊账金额（含水费口径，**不含押金**）—— 列表上色判据之一 */
    private java.math.BigDecimal outstandingCredit;

    /** 最长逾期天数（0 = 没有逾期的）—— 列表上色判据之一 */
    private Integer overdueDays;

    /**
     * 收货地址的楼层 / 是否有电梯（关联查询字段，2026-09-17 新增）。
     *
     * <p><b>为什么要有</b>：P0-2 给 {@code address} 加了这两个字段，但此前只有"计价"在用
     * （向客户收楼层费、给配送员补楼层补贴）——**真正要爬楼的那个人（配送员）看不到**。
     * 楼层费按它收、补贴按它补，配送员出车前却不知道要不要上楼。</p>
     *
     * <p>⚠️ 取的是<b>当前地址</b>的值，不是下单时的快照：配送员要知道"客户现在在哪层"。
     * 计费用的历史口径已经落在 {@code orders.floor_fee} 上，<b>不需要也不应该</b>为此加快照列。</p>
     *
     * <p>⚠️ {@code addressHasElevator} 是<b>三态</b>：{@code null} = 客户没确认过、{@code 0} = 无电梯、
     * {@code 1} = 有电梯。展示时别把 null 说成"无电梯"。</p>
     */
    private Integer addressFloor;

    private Integer addressHasElevator;

    /** 订单归属水站 */
    private Long stationId;

    /** 实际履约配送水站ID，可与 station_id 不同 */
    private Long deliveryStationId;

    /**
     * 结算水站（v47）= <b>本单营收归谁</b>：水费 + 配送费 + 楼层费归它。
     *
     * <p>三列的分工（别混用）：{@code stationId} = 归属站（客户选定的站，<b>定价方</b>）；
     * {@code deliveryStationId} = 履约站（谁去送，库存/配送员/工钱）；本列 = 钱归谁。</p>
     *
     * <p>取值：下单 = {@code stationId}；抢单/定向外派 = 履约站；取消外派/召回/退回池 = 回
     * {@code stationId}。<b>押金 / 水票 / 桶权益仍按归属站</b>，与营收归属是两件事
     * （那是"客户买在哪个站的资产"）。</p>
     *
     * <p>⚠️ 读取一律 {@code coalesce(settle_station_id, delivery_station_id, station_id)}
     * —— 这是<b>防御</b>（漏写的历史/未来行不丢营收），<b>不是常态</b>：正常路径必须写本列。
     * 写入点见 {@code OrderMapper} 的 save 与那 7 条改履约站的 CAS，以及
     * {@code sql/migration_v47_order_settle_station.sql} 的文件头。</p>
     */
    private Long settleStationId;

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
                && Integer.valueOf(PayMethod.CASH).equals(paymentMethod)
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

    /**
     * 是否展示支付入口。
     * <p>只有在「点了就能真的付掉」时才返回 true：
     * <ul>
     *   <li>订单已取消 / 已付款 / 已退款 → false</li>
     *   <li>水票(3)：下单即视同已付，客户无需再操作 → false</li>
     *   <li>现金(2)：货到付款，由配送员送达时收款 → false</li>
     *   <li>微信(1)：尚未接入微信支付渠道，没有在线支付入口 → false</li>
     * </ul>
     * 旧实现对「未付款/待收款」一律返回 true，前端按钮点了只是建一条 PENDING 流水，
     * 却提示"支付成功"，客户以为付了款、配送员上门按未付处理 —— 典型假支付。</p>
     */
    public Boolean getCanRepay() {
        if (status != null && status == OrderStatus.CANCELLED) return false;
        String s = getPayState();
        if (!("UNPAID".equals(s) || "PENDING".equals(s) || "CANCELLED".equals(s))) return false;
        // 三种支付方式当前都没有客户自助在线支付入口，见 payHint 的说明
        return false;
    }

    /** 支付入口按钮文案：支付被取消过显示为「重新支付」，否则「去支付」 */
    public String getRepayLabel() {
        return "CANCELLED".equals(getPayState()) ? "重新支付" : "去支付";
    }

    /**
     * 付款状态说明（客户侧展示，全系统唯一文案来源）。
     * 没有支付入口时，前端渲染这段话解释"钱怎么付"，而不是留一个点了也没用的按钮。
     */
    public String getPayHint() {
        if (status != null && status == OrderStatus.CANCELLED) return "订单已取消";
        switch (getPayState()) {
            case "PAID":      return "已付款";
            case "REFUNDED":  return "已退款";
            case "CANCELLED": return "支付已取消";
            default: break;
        }
        if (paymentMethod != null) {
            if (paymentMethod == PayMethod.TICKET) return "水票支付，下单即视同已付";
            if (paymentMethod == PayMethod.CASH)   return "货到付款，配送员送达时收款";
            if (paymentMethod == PayMethod.WECHAT) return "微信支付暂未开通，请联系水站改用货到付款或水票支付";
        }
        return "待付款";
    }

    // ============ 转单（退回/转让）状态：由后端按 special_note 标记统一判定 ============
    // 背景：配送端订单详情曾读取 transferStatus / returnStatus / isTransferTarget 三个
    // 后端根本不存在的字段（与 collected 同类问题），导致「转单中/退回申请/待你确认」
    // 标签永远不显示。改为后端按真实标记计算后下发。

    /**
     * [AQ-015] 待决策转单类型（STAFF / DIRECTED），由列表 SQL 从 order_transfer 子查询填充。
     * <p>把"转单中"的判定从 special_note 文本标记迁移到结构化表；此字段仅用于承载查询结果，
     * 不落库（orders 表无此列）。</p>
     */
    private String transferPendingKind;

    /** 转单类型：NONE 无 / STAFF 配送员转单 / DIRECTED 站间指定外派退回 */
    public String getTransferKind() {
        // [AQ-015] 优先使用结构化转单记录（order_transfer，权威状态源）
        if (transferPendingKind != null && !transferPendingKind.isEmpty()) {
            return transferPendingKind;
        }
        // 回退：未经查询填充（如单条 getById）或历史数据，仍按 special_note 文本判定，保证兼容
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
        // ⚠️ [Phase 1 待办] 本兜底假定 total_amount = 水费 + 押金。一旦把配送费/楼层费并入
        // total_amount（见 docs/design/16 §3.1），这里必须一并减去 deliveryFee 与 floorFee，
        // 否则「历史订单没回填 water_amount」的那些单，水费会被多算出一个运费。
        BigDecimal water = totalAmount.subtract(deposit);
        return water.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : water;
    }

    /** 押金金额 */
    private BigDecimal depositAmount;

    /**
     * 配送费（2026-09-17 新增，见 {@code sql/migration_v34_delivery_fee_and_floors.sql}）。
     *
     * <p>⚠️ <b>绝不并入 {@link #waterAmount} 或 {@link #depositAmount}</b>：前者污染水费口径
     * （水费与退款、报表、对账都相关）；后者的后果是真丢钱 —— 退款路径按
     * {@code orders.deposit_amount} 释放押金余额（{@code PaymentServiceImpl} 的退款分支），
     * 把运费混进去，取消订单时会<b>多退一份运费到客户押金账户</b>。</p>
     */
    private BigDecimal deliveryFee;

    /**
     * 楼层费（<b>向客户收</b>的那一笔，收入项）。
     *
     * <p>⚠️ 给配送员的「楼层补贴」是<b>另一笔钱</b>（成本项），两者金额可以不同、
     * 必须分别配置分别落库（见 docs/design/18 §4）。合成一个字段会导致客户投诉时查不清、
     * 工钱算不准。</p>
     */
    private BigDecimal floorFee;

    /**
     * 配送员在完成配送时**上报的楼层**（选填，v43）。
     *
     * <p>⚠️ 三态语义：{@code null} = 没上报（楼层补贴沿用<b>地址</b>里的楼层）／有值 = 以他上报的为准。
     * 与地址里的楼层不一致时，收益明细的 note 会打上"与地址 N 层不一致"的标记 ——
     * 楼层补贴是给配送员的钱，只有他知道自己爬了几层，所以要有他自己的口径 + 可核对的凭证
     * （{@code order_image.type=3} 楼层凭证，配送员与站长都可传），见 docs/design/18 §4。</p>
     *
     * <p>⚠️ 它<b>不影响向客户收的楼层费</b>（那是下单时按地址快照的 {@link #floorFee}）。</p>
     */
    private Integer reportedFloor;

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

    /**
     * 定价来源站名（瞬时字段，非数据库列）：跨站单的配送费/楼层费是按<b>归属站</b>
     * （{@code station_id}）当时的站级配置算出来、并快照进 {@link #deliveryFee} / {@link #floorFee} 的，
     * 即"站长外派也按本站定价"。认领/接单前要让目标站一眼看到这个价是谁定的
     * （{@code docs/design/17} 的配送计费 + 2026-09-18 的产品裁定）。
     * <p>由 {@code DeliveryController} 在抢单池 / 他站外派两个列表里填充，其它端点不下发。</p>
     */
    private transient String feeStationName;

    /**
     * 结算去向文案（瞬时字段，非数据库列）：<b>由后端下发，前端不得自造</b>
     * （本仓铁律：金额与口径文案只有一个来源，见 AGENTS.md §6）。
     * <p>抢单池是"认领后"，他站外派是"接单后"—— 两种语境下这句话不一样，
     * 所以文案由填充它的方法决定，前端只负责原样展示。</p>
     */
    private transient String settleNote;

    /**
     * 本单营收是否计入当前登录水站（瞬时字段，非数据库列）。
     * <p>抢单池/他站外派列表里恒为 {@code true}（这些列表本身就是"待本站履约"的单），
     * 下发它是为了让前端不必自己推导"钱归谁"，只按它决定要不要把结算文案显示成强调色。</p>
     */
    private transient Boolean settleToMyStation;

    /** 订单商品明细(关联查询字段) */
    private List<OrderItem> items;
}
