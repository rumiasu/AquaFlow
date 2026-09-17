package com.example.aquaflow.util;

import java.math.BigDecimal;

/**
 * 地理计算工具：站点与客户地址之间的<b>直线距离</b>。
 *
 * <p><b>为什么用直线距离，而不是地图 API 的骑行/驾车距离</b>：桶装水是电动车/三轮车配送，
 * 直线距离对「这一单要不要接」这个判断已经够用；第三方距离 API 有配额与费用，
 * 且一旦引入，测试与本地开发就都要联网。见 {@code docs/design/17} §4.2。</p>
 *
 * <p>⚠️ 本类<b>只做数学，不做任何判断</b> —— 不判断是否超范围，也不决定放行还是拒绝。
 * 坐标可能为 {@code null}（站点没选点、客户地址地图解析失败），此时调用方必须
 * <b>跳过校验并放行</b>，而不是拒单：把「没有数据」当成「超出范围」会把所有客户挡在门外。
 * 这也是 {@link #distanceMeters} 返回 {@code null} 而不是 0 或 -1 的原因：0 是「同一个点」
 * 这个合法值，用哨兵值表达「算不出来」会让调用方把两者混为一谈。</p>
 */
public final class GeoUtil {

    /** 地球平均半径（米）。IUGG 推荐值 6371008.8，取整到 6371000 对配送范围判断足够。 */
    private static final double EARTH_RADIUS_METERS = 6371000d;

    private GeoUtil() {}

    /**
     * 两点间大圆距离（Haversine 公式），单位米。
     *
     * @return 距离（米）；任一点为 {@code null} 或缺经纬度时返回 {@code null}（= 算不出来）
     */
    public static Double distanceMeters(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return null;
        }
        double radLat1 = Math.toRadians(lat1.doubleValue());
        double radLat2 = Math.toRadians(lat2.doubleValue());
        double dLat = radLat2 - radLat1;
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        // 用 atan2 而不是 asin：两点接近对跖点（地球两端）时，asin 形式会因浮点误差让
        // 参数略大于 1 而返回 NaN；atan2 形式在整条区间上都稳定。
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
