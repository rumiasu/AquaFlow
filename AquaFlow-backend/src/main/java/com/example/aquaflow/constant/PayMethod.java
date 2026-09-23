package com.example.aquaflow.constant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付方式（canonical 定义，全端统一）。
 * <p>1=微信 2=现金(货到付款) 3=水票（水票视同已付）。
 * 注意：Orders 实体旧注释曾误写为 "2水票 3线下"，与实现不符，以本类为准。
 * 用户端小程序也曾把 2/3 反过来写（2=水票 3=货到付款），导致默认下单必失败、
 * 选"货到付款"反被当成水票而无人收款。可用支付方式一律由本类下发，前端不再自带映射表。</p>
 */
public class PayMethod {

    /** 1 微信支付 */
    public static final int WECHAT = 1;
    /** 2 现金（货到付款） */
    public static final int CASH = 2;
    /** 3 水票（下单即视同已付，无需现场收款） */
    public static final int TICKET = 3;

    /**
     * 支付方式中文文案（全系统唯一文案来源）。
     * 前端一律渲染后端下发的 payMethodText，禁止自行写映射表。
     */
    public static String textOf(Integer method) {
        if (method == null) return "现金";
        switch (method) {
            case WECHAT: return "微信";
            case CASH:   return "现金";
            case TICKET: return "水票";
            default:     return "现金";
        }
    }

    /** 用户端展示名（与列表选项一致，区别于对账用的短名 textOf） */
    public static String displayNameOf(int method) {
        switch (method) {
            case WECHAT: return "微信支付";
            case CASH:   return "货到付款";
            case TICKET: return "水票支付";
            default:     return "货到付款";
        }
    }

    /**
     * 当前客户可用的支付方式列表（全系统唯一来源，前端据此渲染选项与默认选中项）。
     *
     * <p><b>微信那一项由 {@code wechatPayEnabled} 决定，不再恒为不可用</b>
     * （[2026-09-20] 引入「微信支付模拟渠道」，见 {@code app.payment.mock-wechat-pay}）：
     * 模拟开启时微信可选，文案明确标注是模拟，避免联调的人以为真接了支付。
     * 关闭时行为与之前**逐字一致**（disabled +「暂未开通」），生产默认关闭。</p>
     *
     * @param offlinePaymentAllowed 该客户在该水站是否具备货到付款（现金）权限
     * @param wechatPayEnabled      微信支付渠道当前是否可用（真实接入前 = 模拟渠道开关）
     * @return 每项含 id/name/desc/enabled
     */
    public static List<Map<String, Object>> availableMethods(boolean offlinePaymentAllowed,
                                                            boolean wechatPayEnabled) {
        List<Map<String, Object>> list = new ArrayList<>();
        // 1 微信：真实渠道未接入；只有模拟渠道开启时才可选。
        //    ⚠️ [2026-09-20 裁定 · 别再重复提] 这一项是**新客户第一单的唯一自助通道**。
        //    新客户此刻：0 张水票；货到付款也没开通 —— 而"开通"的闸门要求「绑定 ∪ 本站订单」，
        //    绑定又只能靠下单产生（见 CustomerController.updateOfflinePaymentConfig），是个闭合环。
        //    所以"模拟渠道关 + 真实渠道未接入"时三通道全灰、客户确实下不了第一单 ——
        //    但那是**渠道未接入的部署状态**，不是判据缺陷：本地靠 app.payment.mock-wechat-pay
        //    （点击即成功），生产靠真实渠道接入。
        //    ⇒ 「新客户下不了第一单」已有定论，不要每轮都当新发现的 P0 报一遍。
        list.add(method(WECHAT, wechatPayEnabled,
                wechatPayEnabled ? "模拟支付（点击即成功，仅联调期开启）" : "暂未开通"));
        // 2 现金（货到付款）：需水站开启且客户已授权
        list.add(method(CASH, offlinePaymentAllowed,
                offlinePaymentAllowed ? "配送员送达后现金/扫码支付" : "需水站开通，暂不可用"));
        // 3 水票：始终可选；余额够不够由报价（ticketPay）与下单侧校验，这里只做一句引导 ——
        // 票不够的客户原本要提交后才被后端拦下，在选项上先说清"可以先去买票"能少一次来回
        // （购票入口：「我的 → 我的钱包与桶 → 水票」；票不足的弹窗里另有就近的「去买水票」按钮）。
        // ⚠️ 不写"多买多折"一类话术：本站折扣档为空时那就是假话（见 docs/design/26 §26.12.5）。
        list.add(method(TICKET, true, "使用账户水票抵扣 · 票不足可先购买水票"));
        return list;
    }

    /** 第一个可用的支付方式；全部不可用时回退为现金（下单侧会给出明确报错） */
    public static int defaultMethod(boolean offlinePaymentAllowed, boolean wechatPayEnabled) {
        for (Map<String, Object> m : availableMethods(offlinePaymentAllowed, wechatPayEnabled)) {
            if (Boolean.TRUE.equals(m.get("enabled"))) {
                return ((Integer) m.get("id"));
            }
        }
        return CASH;
    }

    private static Map<String, Object> method(int id, boolean enabled, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", displayNameOf(id));
        m.put("desc", desc);
        m.put("enabled", enabled);
        return m;
    }

    private PayMethod() {}
}
