package com.example.aquaflow.util;

import com.example.aquaflow.entity.Orders;
import lombok.extern.slf4j.Slf4j;

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
 *
 * <p>⚠️ <b>本类是 fail-open 的，新增跨站面必须自己核对调用方 SQL 的列别名</b>
 * （台账登记项 F-21：读不到站别时静默不抹）。Map 形态靠调用方 SQL 显式 {@code as stationId} /
 * {@code as deliveryStationId} 取得判定依据，两列<b>任一读不到</b>时这里把规则<b>跳过</b>，
 * 而不是保守抹除 —— 依据是同站展示场景里"读不到/为空"也会合法出现，一律抹会误伤本站客户自己的
 * 姓名/电话（那是行为语义变更，超出本轮改动的范围）。故本轮只做可见化：读不到站别时打一条 WARN
 * （见 {@link #maskIfCrossStation(Map, String...)}），让漏打在日志里出现。</p>
 *
 * <p><b>风险边界（2026-09-30 复核台账 F-21 的原话"没有任何测试会红"后修正）</b>：
 * Map 形态目前<b>只有一个调用方</b> —— 站长端员工画像的「当前进行中」
 * （{@code StaffServiceImpl#decorateOrders} ← {@code StaffMapper.listCurrentOrders}），
 * 而它有集成用例钉着（{@code CrossStationPoolRiskAndProfileIsolationIntegrationTest
 * #fulfillmentSideKeepsMaskingAfterClaim} 读 {@code GET /api/staff/{id}/profile} 的
 * {@code currentOrders}，断言跨站行 {@code customerName} 为 null）⇒ <b>把这个 SQL 的两列别名删掉，
 * 那条用例会红</b>。真正没有护栏的是<b>将来新增</b>的 Map 形态列表：新写一张"可能含跨站单"的表，
 * 别名漏了不会有任何用例红（因为没人给它写断言），这才是 fail-open 的暴露面。</p>
 *
 * <p><b>新增 / 修改"可能含跨站单"的列表 SQL 时的两点契约</b>：
 * ① 显式 <b>as</b> 出 {@code stationId} 与 {@code deliveryStationId}（MyBatis 的
 * {@code map-underscore-to-camel-case} 对 Map 返回值不生效，不写别名读到的就是 null）；
 * ② 同时补一条集成断言（跨站行的 {@code customerName} 为 null）—— 规则正确性与 SQL 别名是同一条链，
 * <b>没有断言的新面就是 fail-open 的下一个落点</b>。</p>
 */
@Slf4j
public final class CustomerProfileMask {

    private CustomerProfileMask() {
    }

    /**
     * 归属站与履约站不同 = 跨站单。任一为空（池中单、历史缺列的行）按「不是跨站」处理。
     *
     * <p>⚠️ 这个"任一为空 ⇒ 不是跨站"的兜底在 Map 形态下就是 fail-open（读不到站别 = 不抹），
     * 那里必须配套 WARN 才不至于悄无声息 —— 见 {@link #maskIfCrossStation(Map, String...)}。</p>
     */
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
     * <p>⚠️ [F-21 2026-09-30] 该 fail-open 分支现在会打 WARN（只告警、<b>不改判定</b>）：
     * 刻意不用"读不到就抹"来兜底 —— 同一批 row 里既可能是"SQL 没 as 出别名"（该抹没抹，真漏），
     * 也可能是"本站单、站别合法为空"（不该抹），两者在数据上无法区分，一律抹会误伤同站展示的姓名。
     * 判据：日志里出现这条 WARN 时，先照 {@code rowKeys} 核对该列表 SQL 的别名，再决定是不是真漏。</p>
     *
     * @param profileKeys 要置 null 的画像列名（如 {@code customerName} / {@code customerPhone}）
     */
    public static void maskIfCrossStation(Map<String, Object> row, String... profileKeys) {
        if (row == null) {
            return;
        }
        Long ownerStationId = asLong(row.get("stationId"));
        Long deliveryStationId = asLong(row.get("deliveryStationId"));
        if (ownerStationId == null || deliveryStationId == null) {
            // [F-21 2026-09-30] 可见化：疑似调用方 SQL 没 as 出站别 ⇒ 本次**未做**画像抹除。
            // 不再往下走（末级防御是不抹而不是抹，理由见方法 javadoc）。
            log.warn("[CustomerProfileMask] 疑似调用方 SQL 未 as 出 stationId/deliveryStationId，"
                            + "本次未做画像抹除（fail-open）：stationId={}, deliveryStationId={}, "
                            + "是否有键缺失={}, rowKeys={}",
                    ownerStationId, deliveryStationId,
                    !row.containsKey("stationId") || !row.containsKey("deliveryStationId"), row.keySet());
            return;
        }
        if (!isCrossStation(ownerStationId, deliveryStationId)) {
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
