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
     * 是否展示支付入口（**客户自助在线支付**）。
     *
     * <p>只有在「点了就能真的付掉」时才返回 true。本类的默认判断是**保守的 false**
     * （不知道该部署到底开了哪些渠道）；真实结论由服务端按当前渠道能力投影进来
     * （{@code PaymentService.canSelfPay} → {@link #selfPayAllowed}）：
     * <ul>
     *   <li>水票(3)：下单即视同已付；但若支付流水那次请求失败/超时导致仍是未付，
     *       客户可以**对同一张单**重试扣票 → 允许</li>
     *   <li>现金(2)：货到付款，配送员送达时收款 → 不允许自助在线付</li>
     *   <li>微信(1)：只有**模拟渠道开启**时才允许（真实微信渠道未接入，
     *       开着模拟渠道却给"去支付"= 假支付；见 {@code app.payment.mock-wechat-pay}）</li>
     *   <li>已取消 / 已付款 / 已退款 → false</li>
     * </ul>
     * 旧实现对「未付款/待收款」一律返回 true，前端按钮点了只是建一条 PENDING 流水，
     * 却提示"支付成功"，客户以为付了款、配送员上门按未付处理 —— 典型假支付。</p>
     */
    public Boolean getCanRepay() {
        if (selfPayAllowed != null) {
            return selfPayAllowed;
        }
        if (status != null && status == OrderStatus.CANCELLED) return false;
        String s = getPayState();
        if (!("UNPAID".equals(s) || "PENDING".equals(s) || "CANCELLED".equals(s))) return false;
        // 没被投影过（例如列表接口、内部调用）：保守地不给入口
        return false;
    }

    /**
     * 客户自助支付能力（瞬时字段，非数据库列）：由服务端按**当前实际启用的渠道**判定后填充
     * （{@code PaymentService.canSelfPay(order)}，在订单详情端点里写入）。
     * <p>{@code null} = 没有投影过 ⇒ {@link #getCanRepay()} 回落到保守的 false。
     * 标 {@code @JsonIgnore} 是为了只下发计算后的 {@code canRepay}，不下发这个内部开关。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private transient Boolean selfPayAllowed;

    /** 支付入口按钮文案：支付被取消过显示为「重新支付」，否则「去支付」 */
    public String getRepayLabel() {
        return "CANCELLED".equals(getPayState()) ? "重新支付" : "去支付";
    }

    /**
     * 付款状态说明（客户侧展示，全系统唯一文案来源）。
     * 没有支付入口时，前端渲染这段话解释"钱怎么付"，而不是留一个点了也没用的按钮。
     * <p>⚠️ 与 {@link #getCanRepay()} 一样，微信那句的"有没有入口"取决于**当前渠道能力**，
     * 所以不在这里写死"暂未开通"：能付的时候说能付，不能付的时候说清楚去哪儿付。</p>
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
            if (paymentMethod == PayMethod.WECHAT) {
                return Boolean.TRUE.equals(selfPayAllowed)
                        ? "还未付款，可在本页继续支付"
                        : "微信支付暂未开通，请联系水站改用货到付款或水票支付";
            }
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

    /**
     * 待决策转单的**子类型**（{@code TRANSFER} 转让 / {@code RETURN_STATION} 退回站长 /
     * {@code CANCEL_REQUEST} 取消申请 / {@code DIRECTED_RETURN} 指定退回）。
     *
     * <p>[2026-09-29 清单2] 与 {@link #transferPendingKind} 同源：kind='STAFF' 一个值底下
     * 分着三种请求、各有各的决策动作，首页「转单请求」栏靠它分流（转让行给「撤回」、
     * 退回站长行给「同意/拒绝」）。列表走两条 SQL 的子查询，详情由
     * {@code DeliveryTaskController#getOrderDetail} 从 order_transfer 记录回填；不落库。</p>
     */
    private String transferPendingSubKind;

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
     * （{@code order_image.type=3} <b>楼梯凭证</b>，[2026-09-26] 由"楼层凭证"改名；配送员与站长都可传），
     * 见 docs/design/18 §4。⚠️ 完成配送页只在"有争议 / 地址没写清有无电梯"时才让配送员填，
     * 所以 {@code null} 是常态。</p>
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

    /** 客户下单时填写的备注；与配送/站长写入的 special_note 分开保存。 */
    private String customerNote;

    /** 本订单 type=8 配送凭据是否存在（不存在时不得把未知显示为 0）。 */
    private transient Boolean bucketDeliveryRecorded;

    /** 本订单配送凭据累计送出桶数，仅由订单详情服务填充。 */
    private transient Integer deliveredBarrelQty;

    /** 本订单配送凭据累计回收空桶数，仅由订单详情服务填充。 */
    private transient Integer returnedBarrelQty;

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

    /**
     * 是否**本站第一笔买桶订单**（押金桶无需回桶）。
     *
     * <p>下单时由 {@code OrderServiceImpl} 写成「本单要送桶 <b>且</b> 客户在本站还没有桶权益
     * （{@code AssetService.hasBarrelAsset}）」。完成配送时读它：为真则**整段跳过回桶核对**
     * （客户刚从水站买下桶，手上没有空桶可还），见 {@code OrderWorkflowServiceImpl.completeDelivery}。</p>
     *
     * <p>⚠️ [2026-09-26] 判据**只看桶**，不要拿 {@code AssetService.hasStationAsset}（票/押金/桶任一）
     * 来算：先买过水票的客户会被判成"老客户"，于是他的第一笔买桶单要核对回桶、回桶数还被默认填成
     * "送出多少回多少" —— 他手里一个空桶都没有。产品原话：「第一次送达桶确实不需要回收，
     * 把第一次桶送达时的默认回桶值取消掉」。</p>
     *
     * <p>⚠️ <b>有意为之的边界</b>：桶权益只认**已到手**（{@code customer_barrel_asset}，送达入账），
     * 所以"第一单还在配送途中就先下了第二单"时，两单<b>都会</b>被打上首单 —— 那一刻客户手上
     * 确实一个空桶都没有（送出去的桶还在路上），两单各是一批新押金桶，本来就无从回收。
     * <b>不要为了"只让第一单是首单"改成数在途桶</b>：那会让第二单在送达时被要求核对回桶。</p>
     */
    private Boolean firstBarrelOrder;

    /** 首个异常类别 */
    private String exceptionCategory;

    /** 异常次数 */
    private Integer exceptionCount;

    /** 关联的桶异常记录ID */
    private Long barrelExceptionId;

    /**
     * 幂等键：防止重复下单。作用域是 {@code (customer_id, idempotency_key)}（迁移 v62 起），
     * 由客户端生成、**跨重试复用**；缺键的下单请求会被直接拒掉（不再由服务端代生成）。
     */
    private String idempotencyKey;

    /**
     * 幂等请求摘要（SHA-256 十六进制，迁移 v62 起）：同键第二次请求用来判断"是不是同一件事"。
     * <p>相等 → 返回原单；不等 → 拒绝（见 {@code OrderServiceImpl.requestDigest}）。
     * 只覆盖业务字段，**不含**服务端算出的金额，也不含 {@code confirmShortage} 这类控制字段。</p>
     */
    private String requestDigest;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 首个商品名称(列表联查字段) */
    private String firstProductName;

    /**
     * 本单**逐明细**摘要，服务端算好（含单位），列表卡直接用。
     *
     * <p>形状：{@code 商品名 数量单位}，多明细以全角逗号 {@code ，} 相连，
     * 按明细 id 升序（例：{@code 纯净水 3桶，矿泉水 1瓶}）。</p>
     *
     * <p>⚠️ <b>为什么会多出这个字段</b>：配送端首页 / 协同页原先用
     * {@code firstProductName × quantity} 渲染，而 {@code quantity} 是<b>全单总件数</b>
     * （含瓶装水/饮水器）、{@code firstProductName} 只取第一条明细 —— 混合单会显示成
     * 「纯净水 × 4桶」（实测 3 桶水 + 1 瓶水）。列表接口没有 {@code items[]}，
     * 而逐个拉详情是设计明令禁止的 N+1，所以只能由后端补这个投影列。</p>
     *
     * <p>⚠️ <b>单位判据与 {@code util/BarrelScope} 同源</b>（{@code product.category}：
     * 1 桶装水 / 2 瓶装水 / 3 饮水器），SQL 侧是它的镜像 —— 改一处必须改两处，
     * 见 {@code OrderMapper} 各列表 SQL 上方的注释。</p>
     *
     * <p>⚠️ 仅由下面那 5 个配送端列表 SQL 填充（**关联/计算字段，不是 orders 表的列**，
     * 写库会被忽略）。其它列表端点保持 {@code null} = "这个端点没下发摘要"，
     * 前端**不要**在 null 时回退到 {@code firstProductName × quantity} 那套旧渲染。</p>
     */
    private String itemSummary;

    /**
     * 「近一年配送」次数 —— 该客户在**本站**近 365 天内**送到过**的单数
     * （{@code status in (3 已送达, 4 已完成)}）。
     *
     * <p>⚠️ 判据是"送过"，**不是**"成交了"：{@code status = 4} 只代表客户已收货且钱已结清，
     * 刚送达还没确认收款的单停在 3 —— 只数 4 会让配送员刚送完一单就看到「0 次」
     * （2026-09-27 真机反馈的原话）。与 {@code CustomerMapper.countCompletedOrders}（只认 4，
     * 用于消费统计）**是两回事，别互相替代**。</p>
     *
     * <p>仅由配送端**订单详情**填充（关联/计算字段，不是 orders 表的列）。</p>
     */
    private transient Integer historyCount;

    /**
     * 「最近一次」距今天数 —— 该客户在**本站**最近一次下单距现在多少天（无单为 null）。
     *
     * <p>与 {@link #historyCount} 同一批下发；同样是关联/计算字段。</p>
     */
    private transient Integer lastOrderDays;

    /**
     * 本单商品<b>种类数</b>（{@code order_item} 行数；不是件数）。
     *
     * <p>前端据此决定是否显示"共 N 种商品"这类提示；与 {@link #itemSummary} 同一批 SQL 下发，
     * 同样是**关联/计算字段，不是 orders 表的列**。</p>
     *
     * <p>⚠️ 它与 {@code orders.quantity}（全单总件数）<b>不是一回事</b>：混合单里
     * 3 桶水 + 1 瓶水的 {@code quantity} 是 4、{@code itemKindCount} 是 2。
     * 单商品单两者都可能为 1，别用其中一个冒充另一个。</p>
     */
    private Integer itemKindCount;

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
     * <p>由 {@code DeliveryConsoleServiceImpl} 在抢单池 / 指定外派（别站指定给我）两个列表里填充，其它端点不下发。</p>
     */
    private transient String feeStationName;

    /**
     * 外派形态（瞬时字段，非数据库列）：{@code POOL} = <b>一键外派</b>（放进抢单池，谁抢谁送）、
     * {@code DIRECTED} = <b>指定外派</b>（指定到具体水站）。
     *
     * <p>前端首页的「外派」页签按它分两个子页签：一键外派不用管（等别站来抢），
     * 指定外派有业务牵扯（要盯对方接不接、还能召回改派）。</p>
     *
     * <p>⚠️ <b>为什么由后端下发而不是前端自己判断</b>：这两种形态在库里<b>没有结构化标记</b>
     * （{@code order_transfer} 只记转单申请与指定退回，放池 / 指定外派都不写它），
     * 唯一判据是 {@code special_note} 里的 {@code [外派]} 文案 —— 归类只能有**一处**实现，
     * 即 {@code constant.DispatchKind.ofNote}；前端解析自由文本必然与后端分叉。</p>
     */
    private transient String dispatchKind;

    /**
     * 履约站名（瞬时字段，非数据库列）：<b>这单派给了哪个水站</b>。
     *
     * <p>⚠️ 前端首页「外派」列表那一行写的是 {@code item.deliveryStationName}，而这个字段
     * 此前**根本不存在**（{@code listDispatchedOrders} 的 SQL 也没 join 站表）→ 站长的外派列表里
     * 「外派至」整行永远不渲染，只剩一个订单号，判断不了这单派给了谁。
     * 现由 {@code CrossStationDispatchController#getDispatchTracking} 按 {@code delivery_station_id} 填。</p>
     *
     * <p>池中还没人接的单 {@code delivery_station_id} 为空 → 本字段保持 null，
     * 前端据此显示"等别站接单"（**不要**在这里编一个"待认领"之类的假站名）。</p>
     */
    private transient String deliveryStationName;

    /**
     * 「外派久未接单」提示（瞬时字段，非数据库列，[2026-09-27] 产品裁定
     * 「长时间没人接还是给站长弹提示是否按照挂牌价」，正本 {@code docs/design/31} §8.3）。
     *
     * <p>由 {@code CrossStationDispatchController#getDispatchTracking} 在**外派追踪列表**里按判据填充（判据实现在 {@code DeliveryConsoleServiceImpl#staleDispatchHint}）：
     * 本站外派出去 + 还在待配送(1) + 没有配送员({@code delivery_staff_id IS NULL})
     * + 距**最后一次变动**超过阈值小时数。命中时给一句**后端下发的文案**（前端原样展示），
     * 未命中保持 null（前端不渲染那一行）。</p>
     *
     * <p>⚠️ 用 {@code update_time} 而不是 {@code create_time}：站长「召回 → 改派」之后计时应当
     * <b>重新开始</b>；拿建单时间会把刚下就被召回一次的新单报成"久未接单"。</p>
     *
     * <p>⚠️ <b>为什么不做成待办项（{@code PendingItem}）</b>：这条提示的动作（按挂牌价结这单）
     * 与外派列表都在**首页**（`pages/coordination/index`），而首页是 tabBar 页 ——
     * 待办卡走 `wx.navigateTo` 跳不过去；标成 P0 只会让 tab 红点亮起来却找不到那件事。
     * 详见 {@code PendingItem} 里那段撤回说明。</p>
     */
    private transient String dispatchStaleHint;

    /**
     * 结算去向文案（瞬时字段，非数据库列）：<b>由后端下发，前端不得自造</b>
     * （本仓铁律：金额与口径文案只有一个来源，见 AGENTS.md §6）。
     * <p>抢单池是"认领后"，指定外派（别站指定本店）是"接单后"—— 两种语境下这句话不一样，
     * 所以文案由填充它的方法决定，前端只负责原样展示。</p>
     */
    private transient String settleNote;

    /**
     * 备货情况（瞬时字段，非数据库列）：配送端「已备齐 / 还缺哪些商品」的只读投影，
     * 由 {@code InventoryReservationService.prepInfoOfOrder} 生成、
     * {@code DeliveryTaskController#getOrderDetail} 在订单详情里填充（契约工作包 C4）。
     *
     * <p>形状：{@code ready}（布尔）/ {@code shortageTotal}（还缺几桶）/
     * {@code itemsWithoutCredential}（连凭据都没有的明细数）/
     * {@code items:[{productId, productName, needQty, reservedQty, shortage}]}。</p>
     *
     * <p>⚠️ 口径是"实物 − 活跃预留"，**不是** {@code inventory.quantity}；
     * 而且它只是**展示**：真正拦住"少扣一点先把单结了"的是完成配送时那次出库校验
     * （提示可能过期，写动作必须再校验）。</p>
     */
    private transient java.util.Map<String, Object> stockPrep;

    /**
     * 本单营收是否计入当前登录水站（瞬时字段，非数据库列）。
     * <p>抢单池 / 指定外派（别站指定本店）列表里恒为 {@code true}（这些列表本身就是"待本站履约"的单），
     * 下发它是为了让前端不必自己推导"钱归谁"，只按它决定要不要把结算文案显示成强调色。</p>
     */
    private transient Boolean settleToMyStation;

    /** 订单商品明细(关联查询字段) */
    private List<OrderItem> items;
}
