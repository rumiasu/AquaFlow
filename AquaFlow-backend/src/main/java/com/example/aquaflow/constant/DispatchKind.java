package com.example.aquaflow.constant;

/**
 * 外派形态 —— 站长端「外派」页签下**两个子页签**的唯一目录（2026-09-26 产品口径）。
 *
 * <p>产品原话（站长端只有两种外派）："
 * <b>一种是一键外派不用管的，一种是指定外派水站，可能往往有一些业务牵扯</b>"。
 * 所以首页那个页签内置两个：「一键外派」= 放进抢单池谁抢谁送、归属站不用管；
 * 「指定外派」= 指定到具体水站、要盯对方接不接、还能召回改派。</p>
 *
 * <p>⚠️ <b>这两种形态在库里没有结构化标记</b>：{@code order_transfer} 只记转单申请与指定退回，
 * 放池与指定外派都**不写**它。唯一判据是 {@code orders.special_note} 里的 {@code [外派]} 备注文案
 * （见下面的 {@code NOTE_*} 常量，写入口在 {@code OrderWorkflowServiceImpl}）。</p>
 *
 * <p>⚠️ <b>归类只有一处实现</b>（{@link #ofNote}）：前端不许解析 {@code specialNote} 自己判，
 * 两端各判一次迟早分叉。**新增入池/外派入口时备注必须用下面的常量拼** —— 否则该单会被默认判成
 * {@link #DIRECTED}，前端分错子页签且不报错。</p>
 *
 * <p>⚠️ 中文名（一键外派 / 指定外派）是<b>页面结构</b>（页签名），由小程序写死；
 * 本枚举只负责机器可读的代号，不下发中文（与 {@code PendingItem} 那种"标签必须后端下发"不同：
 * 那条规矩针对的是**数据驱动的状态文案**，页签名不是数据）。</p>
 */
public enum DispatchKind {

    /**
     * 一键外派：放进抢单池，谁抢谁送，归属站不用管（只在没被接单前能召回）。
     *
     * <p>码值 {@code POOL} 同时是下发给小程序的 {@code dispatchKind} 取值。</p>
     */
    POOL,

    /**
     * 指定外派：指定到具体水站（含订单详情页那个"临时外派"，它是同一件事的另一个入口）。
     *
     * <p>码值 {@code DIRECTED} 同时是下发给小程序的 {@code dispatchKind} 取值。</p>
     */
    DIRECTED;

    /** 放池（一键外派）—— {@code OrderWorkflowServiceImpl.outsource} 的 {@code targetStationId} 为空那一支。 */
    public static final String NOTE_POOL = "[外派] 站长放入抢单池";

    /** 放池（一键外派）的第二条入口：站长拒单时顺手入池（{@code stationReject}）。 */
    public static final String NOTE_POOL_BY_REJECT = "[外派] 站长拒单后外派";

    /** 指定外派：首页「指定水站外派」。 */
    public static final String NOTE_DIRECTED = "[外派] 站长指定外派至";

    /** 指定外派：订单详情页的临时外派（与上一条同义，只是入口不同）。 */
    public static final String NOTE_DIRECT = "[外派] 从水站 ";

    /**
     * 从订单备注判断外派形态。
     *
     * <p>⚠️ <b>取「最后出现的那条标记」</b>，不是"含池标记就算池"：备注是
     * {@code OrderMapper.appendSpecialNote} <b>按时间追加</b>的（`concat(旧值, 新值)`），
     * 一张单可以先后经历「放池 → 召回 → 指定外派」，此时两种标记都在 ——
     * 先判池的话，会给一张**当前是指定外派**的单贴上"一键外派"的标签（站长按页签操作就会做错事）。</p>
     *
     * <p>⚠️ <b>认不出来时默认取 {@link #DIRECTED}</b>：指定外派是需要站长盯着的那一栏，
     * 判错时让它落在"要处理"里比落在"不用管"（{@link #POOL}）里安全 ——
     * 万一将来新入口忘了用上面的常量拼备注，单子不会被静默藏进「一键外派」。</p>
     */
    public static DispatchKind ofNote(String specialNote) {
        String note = specialNote == null ? "" : specialNote;
        int poolAt = Math.max(note.lastIndexOf(NOTE_POOL), note.lastIndexOf(NOTE_POOL_BY_REJECT));
        int directedAt = Math.max(note.lastIndexOf(NOTE_DIRECTED), note.lastIndexOf(NOTE_DIRECT));
        return poolAt > directedAt ? POOL : DIRECTED;
    }
}
