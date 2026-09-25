package com.example.aquaflow.constant;

import java.util.List;

/**
 * 站长「待办」条目的**唯一目录**：key / 中文标签 / 默认紧急级别。
 *
 * <p>2026-09-19 新增。用途是让分级的归属**不写死在 if 里** —— 见
 * {@code ManagerPendingSummaryController}：端点按本目录逐条算数，级别取
 * {@link #defaultLevel()} 与配置覆盖（{@code aquaflow.pending.level-overrides}）的合并结果。
 * 调整"哪件事算紧急"**只动这里一行、或加一个配置键**，前端一行都不用改
 * （前端只认响应里的 {@code level}，不认识具体 key）。</p>
 *
 * <p>⚠️ <b>级别判据</b>（改之前先读，三条互斥）：
 * <ul>
 *   <li>{@link #P0} —— 不处理就**卡住今天的配送**（客户付了钱在等水）；</li>
 *   <li>{@link #P1} —— 影响钱或他人，但客户不会干等；</li>
 *   <li>{@link #P2} —— 不处理也不出事。</li>
 * </ul>
 * 前端 tab 红点**只报 P0**：P1/P2 一起算的话红点会天天亮着，站长就把它当背景噪音
 * （"告警疲劳"的同形问题）。</p>
 *
 * <p>⚠️ <b>标签是后端下发的唯一来源</b>：前端不得自带 key→中文 映射表
 * （本仓明令，见 AGENTS §6）。</p>
 */
public enum PendingItem {

    // ===== P0：卡住今天的配送 =====

    /** 与「配送/待办」页的 station-pending 同一方法（含"没收到钱的单不进视野"那道闸门）。 */
    PENDING_ASSIGN("pendingAssign", "待分配订单", Level.P0),
    /**
     * 配送员发起的**转单类**申请（退回站长 / 转让 / 重分配）。
     *
     * <p>⚠️ 本条原来用 {@code listTransferredOrders} 计数，而它取的是 {@code kind='STAFF'} 的
     * **整批、不分子类** —— 于是"转单请求"与"站内取消申请"会是同一个数字。现按
     * {@code order_transfer.sub_kind} 拆开：本条只数 {@link #STAFF_TRANSFER_SUB_KINDS}。</p>
     */
    PENDING_TRANSFER("pendingTransfer", "转单请求", Level.P0),
    /** 客户发起的取消申请（订单已被接单，客户不能自助取消，只能申请）。 */
    CUSTOMER_CANCEL("customerCancel", "客户取消申请", Level.P0),
    /** 站内（配送员）发起的取消申请：与转单类同表同 kind，只差 sub_kind。 */
    STATION_CANCEL("stationCancel", "站内取消申请", Level.P0),
    /**
     * 别站定向外派给本站、等本站确认接不接。
     *
     * <p>⚠️ 与「抢单池」不是一回事：抢单池是**机会**（谁都能抢，归 P2），
     * 定向外派是**别人指名给你的**、等一个明确答复 —— 所以它归 P0。
     * 但它的性质是"要不要接"而不是"客户在等水"，是本级别里最弱的一条；
     * 产品若认为该降级，改这一行即可（或加一条配置覆盖）。</p>
     */
    DIRECTED_INCOMING("directedIncoming", "他站定向外派待确认", Level.P0),
    /** 客户已提交退桶申请、等站长确认收桶（{@code barrel_record.status = 1}）。 */
    BARREL_RETURN("barrelReturn", "待审退桶", Level.P0),

    // ===== P1：影响钱或他人，但客户不会干等 =====

    /** 与「待确认收款」页同一个 mapper 方法（含线上购票的无订单流水）。 */
    PENDING_PAYMENT("pendingPayment", "待确认收款", Level.P1),
    /** 逾期应收：取 {@code ReceivableService.overview} 的读数，与首页待办卡同源。 */
    OVERDUE_RECEIVABLE("overdueReceivable", "逾期应收", Level.P1),
    /** 员工绑定 / 解绑申请（同一张待审表，按 type 分流端点）。 */
    STAFF_BINDING("staffBinding", "员工绑定申请", Level.P1),
    /** 企业身份待审；平台总开关关着时后端返回空列表 → 自然为 0、前端不显示。 */
    ENTERPRISE_APPLY("enterpriseApply", "企业身份待审", Level.P1),
    /** 已生成但还没确认的配送员工资结算单。 */
    DRAFT_PAYROLL("draftPayroll", "待确认结算单", Level.P1),

    // ===== P2：不处理也不出事 =====

    /** 抢单池：别的站外派的救援单，**机会不是义务**。 */
    POOL_CLAIMABLE("poolClaimable", "抢单池可抢", Level.P2),
    /** 上架商品里还没填进货成本的数量（不填就算不出毛利）。 */
    COST_NOT_FILLED("costNotFilled", "未填成本", Level.P2),
    /** 配送员已录入、等站长处置的回桶差异。 */
    BARREL_EXCEPTION("barrelException", "待处理桶异常", Level.P2),
    /** 本站运营告警（只读；**不含系统故障告警**，那是发给系统管理员的）。 */
    OPERATION_ALERT("operationAlert", "运营告警", Level.P2);

    /**
     * 紧急级别。
     *
     * <p>⚠️ 刻意用嵌套枚举而不是 {@code public static final String P0 = "P0"}：枚举常量在构造时
     * **不能引用声明在它们之后的静态字段**（Java 的非法前向引用，编译期就报
     * "illegal forward reference"）。用嵌套类型既避开这个坑，又让"级别只有三种"由类型保证。</p>
     *
     * <p>判据（三条互斥）：{@code P0} 不处理就卡住今天的配送；{@code P1} 影响钱或他人但客户不会干等；
     * {@code P2} 不处理也不出事。前端 tab 红点**只报 P0**。</p>
     */
    public enum Level {
        /** 不处理就卡住今天的配送（客户付了钱在等水）。 */
        P0,
        /** 影响钱或他人，但客户不会干等。 */
        P1,
        /** 不处理也不出事。 */
        P2
    }

    /**
     * 「转单请求」包含的子类。
     *
     * <p>⚠️ 与 {@link #SUB_CANCEL_REQUEST} 是**互补**关系：{@code order_transfer} 里
     * {@code kind='STAFF'} 的行按 {@code sub_kind} 分两拨。**新增子类时必须同时决定它归哪一拨** ——
     * 这是刻意写成"枚举互补"而不是"NOT IN 排除"的原因：排除式写法下，
     * 新加一个 sub_kind 会被**静默**算进转单请求，没人会发现。</p>
     */
    public static final List<String> STAFF_TRANSFER_SUB_KINDS =
            List.of("RETURN_STATION", "TRANSFER", "REDISPATCH");

    /** 站内取消申请的子类（与 {@link #STAFF_TRANSFER_SUB_KINDS} 互补，见其注释）。 */
    public static final String SUB_CANCEL_REQUEST = "CANCEL_REQUEST";

    private final String key;
    private final String label;
    private final Level defaultLevel;

    PendingItem(String key, String label, Level defaultLevel) {
        this.key = key;
        this.label = label;
        this.defaultLevel = defaultLevel;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public Level defaultLevel() {
        return defaultLevel;
    }

    /** 解析配置覆盖值；非法（或空）返回 null，由调用方退回默认级别。 */
    public static Level parseLevel(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Level.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
