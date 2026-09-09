package com.example.aquaflow.constant;

/**
 * 支付方式（canonical 定义，全端统一）。
 * <p>1=微信 2=现金(货到付款) 3=水票（水票视同已付）。
 * 注意：Orders 实体旧注释曾误写为 "2水票 3线下"，与实现不符，以本类为准。</p>
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

    private PayMethod() {}
}
