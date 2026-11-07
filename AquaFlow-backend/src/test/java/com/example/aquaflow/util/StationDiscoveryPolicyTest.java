package com.example.aquaflow.util;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class StationDiscoveryPolicyTest {
    @Test void positiveRadiusIncludesExactBoundaryWithoutRoundedDistance() {
        assertTrue(StationDiscoveryPolicy.visible(1000, 1000d, false));
        assertFalse(StationDiscoveryPolicy.visible(1000, 1000.001d, true));
        assertTrue(StationDiscoveryPolicy.visible(1000, 0d, false));
    }
    @Test void positiveRadiusCannotFallBackToCityOrUnknownDistance() {
        assertFalse(StationDiscoveryPolicy.visible(1000, null, true));
        assertFalse(StationDiscoveryPolicy.visible(1000, 2000d, true));
    }
    @Test void nullAndZeroUseTenKilometersOrCity() {
        for (Integer radius : new Integer[]{null, 0}) {
            assertTrue(StationDiscoveryPolicy.visible(radius, 10000d, false));
            assertFalse(StationDiscoveryPolicy.visible(radius, 10000.001d, false));
            assertTrue(StationDiscoveryPolicy.visible(radius, 1000000d, true));
            assertTrue(StationDiscoveryPolicy.visible(radius, null, true));
            assertFalse(StationDiscoveryPolicy.visible(radius, null, false));
        }
    }
    @Test void InvalidDistanceAndCorruptRadiusNeverSatisfyDistance() {
        for (Double distance : new Double[]{null, -1d, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(StationDiscoveryPolicy.visible(1000, distance, true));
            assertFalse(StationDiscoveryPolicy.visible(null, distance, false));
        }
        assertFalse(StationDiscoveryPolicy.visible(-1, 0d, true));
    }
    @Test void coordinateValidationDoesNotConfuseZeroAndMissing() {
        assertTrue(StationDiscoveryPolicy.validCoordinates(BigDecimal.ZERO, BigDecimal.ZERO));
        assertTrue(StationDiscoveryPolicy.validCoordinates(new BigDecimal("-90"), new BigDecimal("180")));
        assertFalse(StationDiscoveryPolicy.validCoordinates(null, BigDecimal.ZERO));
        assertFalse(StationDiscoveryPolicy.validCoordinates(BigDecimal.ZERO, null));
        assertFalse(StationDiscoveryPolicy.validCoordinates(new BigDecimal("90.001"), BigDecimal.ZERO));
        assertFalse(StationDiscoveryPolicy.validCoordinates(BigDecimal.ZERO, new BigDecimal("-180.001")));
        assertFalse(StationDiscoveryPolicy.validCoordinates(new BigDecimal("1E10000"), BigDecimal.ZERO));
    }
    @Test void provinceAndCityMustBeRecognizableAndComplete() {
        assertTrue(StationDiscoveryPolicy.validRegion(" 山东省 ", " 济南市 "));
        for (String province : new String[]{null, "", "山东", "不存在省"})
            assertFalse(StationDiscoveryPolicy.validRegion(province, "济南市"));
        for (String city : new String[]{null, "", "市", "济南", "市辖区", "省直辖县级行政区划", "济南市历下区"})
            assertFalse(StationDiscoveryPolicy.validRegion("山东省", city));
    }
    @Test void cityUsesAddressPrefixNotArbitrarySubstringAndKeepsProvince() {
        assertTrue(StationDiscoveryPolicy.sameCity("山东省济南市历下区测试路", "山东省", "济南市"));
        assertFalse(StationDiscoveryPolicy.sameCity("济南市历下区测试路", "山东省", "济南市"));
        assertFalse(StationDiscoveryPolicy.sameCity("江苏省南京市山东省济南市路", "山东省", "济南市"));
        assertFalse(StationDiscoveryPolicy.sameCity("河北省朝阳市测试路", "辽宁省", "朝阳市"));
        assertFalse(StationDiscoveryPolicy.sameCity(null, "山东省", "济南市"));
    }
    @Test void municipalitiesAcceptCanonicalAndPickerCityWithoutDuplicateProvince() {
        for (String city : new String[]{"北京市", "市辖区"}) {
            assertTrue(StationDiscoveryPolicy.sameCity("北京市朝阳区合成路", "北京市", city));
            assertTrue(StationDiscoveryPolicy.sameCity("北京市北京市朝阳区合成路", "北京市", city));
            assertFalse(StationDiscoveryPolicy.sameCity("河北省保定市北京路", "北京市", city));
        }
        assertFalse(StationDiscoveryPolicy.sameCity("北京市朝阳区", "北京市", "上海市"));
    }
    @Test void compoundPrefectureAndCountyCityCannotBecomeSameCityCondition() {
        String[][] regions = {
                {"吉林省", "延边朝鲜族自治州延吉市"},
                {"新疆维吾尔自治区", "阿克苏地区阿克苏市"},
                {"内蒙古自治区", "锡林郭勒盟锡林浩特市"},
                {"山东省", "济南市章丘市"}
        };
        for (String[] region : regions) {
            assertFalse(StationDiscoveryPolicy.validRegion(region[0], region[1]));
            assertFalse(StationDiscoveryPolicy.sameCity(region[0] + region[1] + "合成路", region[0], region[1]));
        }
    }
    @Test void shortenedPrefectureCannotPrefixAnotherCity() {
        String[][] regions = {
                {"吉林省", "延边州延吉市"}, {"四川省", "凉山州西昌市"},
                {"新疆维吾尔自治区", "伊犁州伊宁市"}, {"贵州省", "黔东南州凯里市"}
        };
        for (String[] region : regions) {
            assertFalse(StationDiscoveryPolicy.validRegion(region[0], region[1]));
            assertFalse(StationDiscoveryPolicy.sameCity(region[0] + region[1] + "合成路", region[0], region[1]));
        }
    }
    @Test void ordinaryZhouCityNamesAreNotMistakenForCompoundPrefectures() {
        String[][] regions = {
                {"浙江省", "杭州市"}, {"广东省", "广州市"}, {"江苏省", "苏州市"},
                {"河南省", "郑州市"}, {"福建省", "福州市"}, {"甘肃省", "兰州市"},
                {"广西壮族自治区", "梧州市"}
        };
        for (String[] region : regions) {
            assertTrue(StationDiscoveryPolicy.validRegion(region[0], region[1]));
            assertTrue(StationDiscoveryPolicy.sameCity(region[0] + region[1] + "合成路", region[0], region[1]));
        }
    }
    @Test void autonomousPrefecturesRegionsAndLeaguesRetainAdministrativeNames() {
        assertTrue(StationDiscoveryPolicy.sameCity("吉林省延边朝鲜族自治州延吉市", "吉林省", "延边朝鲜族自治州"));
        assertTrue(StationDiscoveryPolicy.sameCity("新疆维吾尔自治区阿克苏地区阿克苏市", "新疆维吾尔自治区", "阿克苏地区"));
        assertTrue(StationDiscoveryPolicy.sameCity("内蒙古自治区锡林郭勒盟合成路", "内蒙古自治区", "锡林郭勒盟"));
        assertFalse(StationDiscoveryPolicy.sameCity("云南省大理白族自治州", "吉林省", "延边朝鲜族自治州"));
    }
}
