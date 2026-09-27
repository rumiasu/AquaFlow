package com.example.aquaflow.constant;

/**
 * 水站<b>营业状态</b>（软状态）—— 2026-09-17 新增，配合站长留言一起展示给顾客。
 *
 * <p><b>与 {@code station.status}（硬状态）的区别，别混：</b></p>
 * <ul>
 *   <li>{@code station.status}：1 营业 / 2 停业。**停业会真的拒绝下单**
 *       （{@code OrderServiceImpl} 校验 + {@code /api/stations/public} 只列 status=1），
 *       是"这家站没了/关张"的开关。</li>
 *   <li>本枚举：**软状态，一律不阻断下单** —— 顾客照常下单，只是会看到提示
 *       （商城/下单页横幅 + 下单响应 {@code warnings}）。站长用来表达"现在什么情况"。</li>
 * </ul>
 *
 * <p>产品口径（2026-09-17 与站长确认）：「正常运营、休息等」，**不做强制拦截**，
 * 所以这里不做任何下单校验，只负责文案与提示。文案由后端下发，前端禁止自带映射表。</p>
 *
 * <p>⚠️ <b>默认值于 2026-09-23 改成 {@link #PENDING_LAUNCH 待上线}</b>：新注册水站一律「待上线」，
 * 资料配齐后由站长手动切「正常运营」（列默认值改动见
 * {@code sql/migration_v61_station_pending_launch.sql}）。在那之前默认是 {@link #NORMAL}。</p>
 */
public final class StationOperatingStatus {

    /** 正常运营 */
    public static final int NORMAL = 1;

    /** 休息中（打烊/午休等，稍后恢复；留言里写恢复时间） */
    public static final int RESTING = 2;

    /**
     * <b>待上线</b> —— <b>新注册水站的默认状态</b>（2026-09-23 起；列默认值改动见
     * {@code sql/migration_v61_station_pending_launch.sql}）。
     *
     * <p>含义：这家站还没把上线必需的资料配好（缺坐标 / 没上架商品 / 没保存过配送计费…）。
     * 站长端在营业状态下方列出**待填项**并支持点击直达，清单判据在
     * {@code StationSetupGuideService} —— <b>不在这里重复</b>（本仓"规则目录是单一数据源"）。</p>
     *
     * <p>⚠️ <b>墓碑（2026-09-23）</b>：本值 3 原为「<b>配送延迟</b>」（照常接单、送达会比平时晚：
     * 爆单 / 天气 / 人手不足）。产品裁定**用它换「待上线」**，该表达从此不存在 ——
     * 要表达"晚送"请用 {@link #RESTING 休息中}，或写进 {@code status_note} 留言。
     * <b>不要再把 3 当「配送延迟」用。</b></p>
     */
    public static final int PENDING_LAUNCH = 3;

    /** 暂停接单、可预约（今天不送了，订单统一明天处理） */
    public static final int APPOINTMENT_ONLY = 4;

    private StationOperatingStatus() {}

    /**
     * 全部合法取值（含 {@link #PENDING_LAUNCH}）。**校验合法性**用它，
     * **给站长做选择器**不要用它 —— 见 {@link #SELECTABLE}。
     */
    public static final int[] ALL = { NORMAL, RESTING, PENDING_LAUNCH, APPOINTMENT_ONLY };

    /**
     * 站长**可以自己设**的取值 = 选择器选项（2026-09-24 产品裁定）。
     *
     * <p>⚠️ 与 {@link #ALL} 的差别只有一项：**「待上线」不在里面**。它是**系统状态** ——
     * 只有刚注册的站才会是它，配齐必填项后转正成「正常运营」，**且再也回不去**
     * （判据与单向门在 {@code ManagerStationStatusController#update}）。
     * 让站长能手动设/取消它，等于给了他一个"把自家站从客户视野里摘掉再放回来"的开关，
     * 那不是这个状态要表达的东西。</p>
     *
     * <p>后台报「取值非法」也用这一份：错误提示是站长唯一能看到的"合法取值说明书"，
     * 上面列出一个他本来就设不了的值只会让人困惑。</p>
     */
    public static final int[] SELECTABLE = { NORMAL, RESTING, APPOINTMENT_ONLY };

    /** 给用户看的**可选**取值清单（用于「取值非法」这类错误提示） */
    public static String selectableText() {
        return textOf(SELECTABLE);
    }

    /** 给用户看的取值清单（含系统状态；仅在需要罗列全部取值时用） */
    public static String allText() {
        return textOf(ALL);
    }

    private static String textOf(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int v : values) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(v).append(' ').append(textOf(v));
        }
        return sb.toString();
    }

    public static boolean isValid(Integer status) {
        return status != null && status >= NORMAL && status <= APPOINTMENT_ONLY;
    }

    /** 站长能不能自己把它设成营业状态（{@link #PENDING_LAUNCH} 不能）。 */
    public static boolean isSelectable(Integer status) {
        if (!isValid(status)) {
            return false;
        }
        for (int v : SELECTABLE) {
            if (v == status) {
                return true;
            }
        }
        return false;
    }

    /** 是否「待上线」（新站默认；站长端据此展示待填项引导）。 */
    public static boolean isPendingLaunch(Integer status) {
        return status != null && status == PENDING_LAUNCH;
    }

    /** 状态中文文案（全系统唯一来源：后端下发，前端不要自己写 1..4 映射表） */
    public static String textOf(Integer status) {
        if (status == null) return "正常运营";
        switch (status) {
            case NORMAL: return "正常运营";
            case RESTING: return "休息中";
            case PENDING_LAUNCH: return "待上线";
            case APPOINTMENT_ONLY: return "暂停配送，可预约";
            default: return "正常运营";
        }
    }

    /**
     * 选项的一句话说明（站长端「营业状态」选择器用，前端不要自带映射表）。
     * <p>与 {@link #textOf} 同源，改文案只改这里。</p>
     */
    public static String descOf(Integer status) {
        if (status == null) return "";
        switch (status) {
            case NORMAL: return "照常接单、照常配送";
            case RESTING: return "暂时打烊，稍后恢复（留言里写恢复时间）";
            case PENDING_LAUNCH: return "资料还没配齐，尚未正式营业（下方列了待填项）";
            case APPOINTMENT_ONLY: return "今天不送了，订单统一明天处理";
            default: return "";
        }
    }

    /**
     * 给顾客看的一句话提示（下单响应 warnings 与页面横幅共用）。
     * <p>正常运营返回 {@code null} —— 调用方据此决定"不提示"。</p>
     */
    public static String customerHint(Integer status, String note) {
        if (status == null || status == NORMAL) return null;
        String base;
        if (status == PENDING_LAUNCH) {
            // 待上线是"这家站还没准备好"，语气与"休息中/暂停配送"不同，单独给一句
            base = "水站正在完善上线资料，尚未正式营业";
        } else {
            base = "水站当前：" + textOf(status);
        }
        if (note != null && !note.trim().isEmpty()) {
            base += " · " + note.trim();
        }
        if (status == PENDING_LAUNCH) {
            // ⚠️ **待上线的站对顾客不可见**（`StationMapper.listPublic` 与 `StationController.search`
            //    都把 3 滤掉了）—— 顾客根本发现不了这家站，**所以这里不能说"仍可下单"**。
            //    2026-09-24 修：原先把"（仍可下单…）"无条件拼在所有非正常状态后面，对待上线是假话。
            return base + "（暂不对外营业，顾客在小程序里找不到这家水站）";
        }
        // 明确的"不阻断"口径：让客户知道还能下单，避免误以为下不了单
        return base + "（仍可下单，站长会按上面的说明安排配送）";
    }

    /**
     * 「这个状态对顾客意味着什么」—— 站长端「营业状态」页**点哪个状态就显示哪一句**（2026-09-24 产品要求）。
     *
     * <p>它取代了原来那条固定的「顾客会看到：…」预览栏：那条既与选项自身的说明重复，
     * 又是**前端自己拼的句子**（`statusOptions[picked-1].name + '（仍可下单…）'`），
     * 而拼出来的"仍可下单"对待上线恰恰是错的。文案一律由后端下发，前端只渲染。</p>
     */
    public static String effectOf(Integer status) {
        if (status == null) return "";
        switch (status) {
            case NORMAL:
                return "顾客正常下单，商城与下单页都不显示任何提示。";
            case RESTING:
                return "顾客照常下单，但会在商城与下单页看到「休息中」和你的留言。";
            case PENDING_LAUNCH:
                return "顾客看不到这家水站 —— 选站列表与搜索里都没有它，也就无法下单。"
                        + "等你把资料配齐、转成「正常运营」之后，它才会出现在顾客面前。";
            case APPOINTMENT_ONLY:
                return "顾客照常下单，但会看到「暂停配送，可预约」和你的留言。";
            default:
                return "";
        }
    }
}
