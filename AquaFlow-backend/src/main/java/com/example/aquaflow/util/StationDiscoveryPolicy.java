package com.example.aquaflow.util;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅用于客户发现列表；不得复用于历史资产可见性或订单计费。 */
public final class StationDiscoveryPolicy {
    private static final double DEFAULT_RADIUS_METERS = 10_000d;
    private static final Set<String> MUNICIPALITIES = Set.of("北京市", "天津市", "上海市", "重庆市");
    private static final Set<String> PROVINCES = Set.of(
            "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省", "江苏省", "浙江省", "安徽省",
            "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省", "广东省", "海南省",
            "四川省", "贵州省", "云南省", "陕西省", "甘肃省", "青海省", "台湾省",
            "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区");
    private static final Pattern CITY = Pattern.compile("[\\p{IsHan}]{2,30}(?:自治州|地区|盟|市)");

    private static final Pattern CITY_BOUNDARY = Pattern.compile("自治州|地区|盟|市");

    private StationDiscoveryPolicy() { }

    /** 两个 0 是有效坐标；缺值、越界或过大的数值都不能转为距离零。 */
    public static boolean validCoordinates(BigDecimal lat, BigDecimal lng) {
        return lat != null && lng != null
                && lat.compareTo(BigDecimal.valueOf(-90)) >= 0 && lat.compareTo(BigDecimal.valueOf(90)) <= 0
                && lng.compareTo(BigDecimal.valueOf(-180)) >= 0 && lng.compareTo(BigDecimal.valueOf(180)) <= 0;
    }

    /** 只有完整且可辨认的省市才构成兜底条件；不将同名异省城市合并。 */
    public static boolean validRegion(String province, String city) {
        String p = clean(province), c = clean(city);
        if (MUNICIPALITIES.contains(p)) return c.equals(p) || c.equals("市辖区");
        if (!PROVINCES.contains(p) || !CITY.matcher(c).matches()) return false;
        // 地图地址解析可能给出“自治州 + 县级市”；首个行政边界必须就是城市名结尾。
        var boundary = CITY_BOUNDARY.matcher(c);
        if (!boundary.find() || boundary.end() != c.length()) return false;
        // 2026-11-07：还须拒绝“延边州延吉市”等简称复合名；不能把杭州、广州中的“州”误当边界。
        int prefecture = c.indexOf('州');
        return prefecture < 0 || prefecture == c.length() - 1
                || (prefecture == c.length() - 2 && c.endsWith("市"));
    }

    /**
     * 2026-11-07：station 没有结构化城市，只能匹配完整行政区前缀。
     * 不做 contains 或省份缺失推断，否则店名/路名里的异地城市会被误认为同城。
     */
    public static boolean sameCity(String address, String province, String city) {
        if (!validRegion(province, city) || address == null) return false;
        String p = clean(province), c = clean(city), a = address.trim();
        return a.startsWith(MUNICIPALITIES.contains(p) ? p : p + c);
    }

    /** 正数半径独占判定；不限半径按 10 公里或明确同城，不沿用下单的缺坐标放行。 */
    public static boolean visible(Integer radius, Double meters, boolean sameCity) {
        boolean knownDistance = meters != null && Double.isFinite(meters) && meters >= 0;
        if (radius != null && radius > 0) return knownDistance && meters <= radius;
        if (radius != null && radius < 0) return false;
        return (knownDistance && meters <= DEFAULT_RADIUS_METERS) || sameCity;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
