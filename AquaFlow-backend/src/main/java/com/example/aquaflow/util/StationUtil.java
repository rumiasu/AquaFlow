package com.example.aquaflow.util;

import com.example.aquaflow.entity.Orders;

/**
 * 订单履约站/归属站语义工具。
 * orders.station_id = 订单归属站
 * orders.delivery_station_id = 实际履约站
 */
public class StationUtil {

    /**
     * 订单履约站：优先 delivery_station_id，回退 station_id (订单归属站)。
     * 用于库存扣减、配送判站、站端列表过滤等一切"谁履约"的判断。
     */
    public static Long deliveryStation(Orders o) {
        if (o == null) return null;
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    /**
     * 订单归属站：直接返回 station_id (订单归属站)。
     * 用于"订单属于哪站"的判断（账务统计、站端列表过滤等）。
     */
    public static Long station(Orders o) {
        if (o == null) return null;
        return o.getStationId();
    }

    /**
     * 订单**结算站**：这笔订单的**营收（水费 + 配送费 + 楼层费）归谁**。
     * <p>取值顺序 {@code settle_station_id → delivery_station_id → station_id}：
     * 下单时 = 归属站；抢单 / 定向外派成功后 = 履约站；召回 / 退回池回归属站。
     * 迁移与语义正本见 {@code sql/migration_v47_order_settle_station.sql}。</p>
     *
     * <p>⚠️ <b>只有"钱"用它</b>。三类口径不要混：<br>
     * · <b>营收（钱）</b> → 本方法 / SQL 里同一套 coalesce（看板、毛利、应收、收款与退款的判权）；<br>
     * · <b>客户资产（押金 / 水票 / 桶权益）</b> → {@link #station(Orders)}（归属站）——
     *   那是客户在哪个站买的账，与谁去送无关；<br>
     * · <b>库存与工钱</b> → {@link #deliveryStation(Orders)}（履约站）。</p>
     *
     * <p>最后一级回退是**防御**（万一有漏写的行也不至于让营收凭空消失），
     * 不是常态：正常写入路径必须落 {@code settle_station_id}。</p>
     */
    public static Long settleStation(Orders o) {
        if (o == null) return null;
        if (o.getSettleStationId() != null) return o.getSettleStationId();
        return deliveryStation(o);
    }
}