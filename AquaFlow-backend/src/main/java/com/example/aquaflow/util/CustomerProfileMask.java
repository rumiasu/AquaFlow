package com.example.aquaflow.util;

import com.example.aquaflow.entity.Orders;

import java.util.Map;

/**
 * 「跨站单不下发客户画像」这条规则的<b>唯一实现</b>（2026-09-18 产品裁定）。
 *
 * <p>口径：一行订单只要<b>履约站 ≠ 归属站</b>，履约站这一侧看到的 {@code customerName} /
 * {@code customerPhone}（{@code left join customer} 带出来的，是<b>归属站</b>的客户档案）
 * 一律置 null；订单自身的 {@code receiverName} / {@code receiverPhone} / 地址 / 商品 / 金额快照
 * <b>照常下发</b> —— 那是「订单有关的信息」，跨站配送必需（点名收件人、送对地址）。</p>
 *
 * <p>⚠️ 为什么是「保留键、显式置 null」而不是从 SQL 里删掉那两列：① 形状统一 —— 前端对同一种情况
 * 只需一套判断，且键仍在能区分"后端刻意不下发"与"客户端拿着旧后端"；② 抢单池 / 他站外派那两张列表
 * <b>整表都是别站客户</b>（池中单 {@code delivery_station_id} 为空，用跨站判定反而判不出来），
 * 所以必须同时提供无条件抹除的 {@link #mask(Orders)}。</p>
 *
 * <p>⚠️ 判定只看 {@code orders} 自己的两列，<b>不看 customer 表</b>：客户是全局身份、表上没有站别，
 * 「这是不是本站客户」在<b>端点准入</b>层面由「绑定 ∪ 本站订单」判（AGENTS §1.1 的
 * {@code CustomerMapper.countCustomerOfStation}）；这里判的是另一件事 —— 这一行订单上的画像算谁的。
 * 两者不可互相替代：把这条规则当准入用会漏（别站客户仍能在客户列表里被搜到），
 * 把准入当这条用会误伤（本站客户的他站履约单会被当成"陌生客户"）。</p>
 */
public final class CustomerProfileMask {

    private CustomerProfileMask() {
    }

    /** 归属站与履约站不同 = 跨站单。任一为空（池中单、历史缺列的行）按「不是跨站」处理。 */
    public static boolean isCrossStation(Long ownerStationId, Long deliveryStationId) {
        return ownerStationId != null && deliveryStationId != null && !ownerStationId.equals(deliveryStationId);
    }

    /** 无条件抹除：整表都是别站客户的列表（抢单池、他站外派给我）。 */
    public static void mask(Orders order) {
        if (order == null) {
            return;
        }
        order.setCustomerName(null);
        order.setCustomerPhone(null);
    }

    /** 只抹跨站行：同一张表里混着本站自己的单（配送员任务列表 / 历史 / 回桶记录 / 站长待分配）。 */
    public static void maskIfCrossStation(Orders order) {
        if (order == null || !isCrossStation(order.getStationId(), order.getDeliveryStationId())) {
            return;
        }
        mask(order);
    }

    /**
     * Map 形态的同一规则。
     *
     * <p>⚠️ 调用方的 SQL <b>必须显式 as 出</b> {@code stationId} / {@code deliveryStationId} 两列：
     * MyBatis 的 {@code map-underscore-to-camel-case} <b>对 Map 返回值不生效</b>，
     * 不写别名这里读到的就是 {@code null} —— 而"读不到"被判成「不是跨站」，表现是<b>静默不抹</b>。</p>
     *
     * @param profileKeys 要置 null 的画像列名（如 {@code customerName} / {@code customerPhone}）
     */
    public static void maskIfCrossStation(Map<String, Object> row, String... profileKeys) {
        if (row == null || !isCrossStation(asLong(row.get("stationId")), asLong(row.get("deliveryStationId")))) {
            return;
        }
        for (String key : profileKeys) {
            row.put(key, null);
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
