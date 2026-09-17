package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeoUtil} 的单元测试。
 *
 * <p><b>本仓第一个非集成用例</b>（不继承 {@code AbstractIntegrationTest}、不起 Spring 容器、
 * 不连库），因此它跑得极快。之所以可以这样：被测的是纯函数，没有任何依赖 ——
 * 这类工具就该用单元测试，而不是为了凑"测试都在 integration 包"的整齐去起一个容器。</p>
 *
 * <p>参考值取自球面几何而非"某次实测"：纬度相差 1 度对应的大圆距离恒为
 * {@code π/180 × R} = 111194.93 米（R 取 6371000）。</p>
 */
class GeoUtilTest {

    /** 1 度纬度对应的大圆距离：π/180 × 6371000 */
    private static final double ONE_DEGREE_LAT_METERS = Math.PI / 180 * 6371000d;

    @Test
    @DisplayName("同一个点距离为 0（而不是 null —— 0 是合法值，不能被当成算不出来）")
    void samePointIsZero() {
        Double d = GeoUtil.distanceMeters(bd("39.908700"), bd("116.397500"),
                bd("39.908700"), bd("116.397500"));
        assertEquals(0d, d, 1e-6);
    }

    @Test
    @DisplayName("纬度相差 1 度 ≈ 111195 米")
    void oneDegreeOfLatitude() {
        Double d = GeoUtil.distanceMeters(bd("0"), bd("0"), bd("1"), bd("0"));
        assertEquals(ONE_DEGREE_LAT_METERS, d, 1d);
    }

    @Test
    @DisplayName("赤道上经度相差 1 度 ≈ 111195 米（与纬度对称）")
    void oneDegreeOfLongitudeAtEquator() {
        Double d = GeoUtil.distanceMeters(bd("0"), bd("0"), bd("0"), bd("1"));
        assertEquals(ONE_DEGREE_LAT_METERS, d, 1d);
    }

    @Test
    @DisplayName("对称性：A→B 与 B→A 相等")
    void symmetric() {
        Double ab = GeoUtil.distanceMeters(bd("39.9087"), bd("116.3975"), bd("31.2304"), bd("121.4737"));
        Double ba = GeoUtil.distanceMeters(bd("31.2304"), bd("121.4737"), bd("39.9087"), bd("116.3975"));
        assertEquals(ab, ba, 1e-6);
    }

    @Test
    @DisplayName("北京到上海约 1067 公里（量级正确，不是把度当成米）")
    void beijingToShanghaiOrderOfMagnitude() {
        Double d = GeoUtil.distanceMeters(bd("39.9087"), bd("116.3975"), bd("31.2304"), bd("121.4737"));
        // 已知大圆距离约 1067 km；给 ±20 km 余量，只验证量级与公式没写反
        assertTrue(d > 1_047_000 && d < 1_087_000, "北京→上海应约 1067 km，实际=" + d);
    }

    @Test
    @DisplayName("近对跖点不返回 NaN（asin 写法会在这里翻车，所以用 atan2）")
    void antipodalDoesNotReturnNaN() {
        Double d = GeoUtil.distanceMeters(bd("0"), bd("0"), bd("0"), bd("180"));
        assertTrue(d != null && !d.isNaN(), "对跖点距离不应为 NaN，实际=" + d);
        assertEquals(Math.PI * 6371000d, d, 1d);
    }

    @Test
    @DisplayName("任一坐标为 null 或缺少经纬度 → 返回 null（调用方据此跳过校验并放行）")
    void nullCoordinatesYieldNull() {
        assertNull(GeoUtil.distanceMeters(null, null, bd("39.9"), bd("116.4")), "站点没选点");
        assertNull(GeoUtil.distanceMeters(bd("39.9"), bd("116.4"), null, null), "地址没有坐标");
        assertNull(GeoUtil.distanceMeters(bd("39.9"), null, bd("39.9"), bd("116.4")), "只缺经度也算不出来");
        assertNull(GeoUtil.distanceMeters(null, null, null, null));
    }

    @Test
    @DisplayName("配送范围判定的真实量级：1 公里内/外的两点能被区分")
    void distinguishesWithinAndBeyondOneKilometer() {
        // 同纬度上 0.009 度经度 ≈ 1000 米（在北纬 39.9 约 770 米），用来验证判定阈值可用
        Double near = GeoUtil.distanceMeters(bd("39.9000"), bd("116.4000"), bd("39.9000"), bd("116.4050"));
        Double far = GeoUtil.distanceMeters(bd("39.9000"), bd("116.4000"), bd("39.9000"), bd("116.4200"));
        assertTrue(near < 1000, "0.005 度经度应在 1 公里内，实际=" + near);
        assertTrue(far > 1000, "0.02 度经度应超过 1 公里，实际=" + far);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
