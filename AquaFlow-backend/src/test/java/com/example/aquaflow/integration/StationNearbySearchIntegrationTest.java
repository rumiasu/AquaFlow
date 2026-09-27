package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送员「找水站」的就近排序（{@code GET /api/stations/search?lat=&lng=}，2026-09-26）。
 *
 * <p><b>为什么加这个能力</b>：配送员是在某个片区跑单的人，而改造前这个端点（不传 keyword 时）
 * 直接把前 50 家水站全列出来，申请人很容易挑到一家"自己根本跑不到"的站 ——
 * 那种申请必然被站长拒，白占一次审批。现在手机定位随请求下发，服务端按直线距离升序返回。</p>
 *
 * <p><b>本类钉住四条判据</b>（都是"改一行就会静默变坏"的那种）：</p>
 * <ol>
 *   <li><b>就近排序</b>：传坐标后按距离升序，并带 {@code distanceKm}；</li>
 *   <li><b>不传坐标 = 老行为</b>：定位被拒/老客户端仍要能用，且**不报错**；</li>
 *   <li><b>没设坐标的站不静默丢弃</b>：{@code station.lat/lng} 可为 NULL，此时距离算不出来，
 *       这类站排在带距离的站之后照常返回（{@code distanceKm=null}）——
 *       把"没有数据"当成"超出范围"会让一家真实存在的站凭空消失；</li>
 *   <li><b>停业与待上线不可被申请</b>：前者下单会被拒、后者还没转正，让配送员申请它们只会白跑。</li>
 * </ol>
 */
class StationNearbySearchIntegrationTest extends AbstractIntegrationTest {

    /** 直接插一家带坐标的站（夹具 createStation 不写坐标）。 */
    private long stationAt(String name, String lat, String lng) {
        return insert("INSERT INTO station(name, status, operating_status, lat, lng) VALUES (?, 1, 1, ?, ?)",
                name, lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng));
    }

    /** 搜索结果里按顺序取出 id。 */
    private List<Long> idsOf(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode s : data) {
            ids.add(s.path("id").asLong());
        }
        return ids;
    }

    private JsonNode nodeOf(JsonNode data, long id) {
        for (JsonNode s : data) {
            if (s.path("id").asLong() == id) {
                return s;
            }
        }
        return null;
    }

    @Test
    @DisplayName("传坐标：按离我的距离由近到远，并给出 distanceKm")
    void sortedByDistanceWhenLocationProvided() {
        // 济南（117.06, 36.68）为原点；近站 ~1.5km，远站 ~30km
        long near = stationAt("近站", "36.690000", "117.070000");
        long far = stationAt("远站", "36.950000", "117.070000");
        long noCoord = stationAt("没设坐标的站", null, null);

        // 不传坐标：老行为，三家的相对顺序不做要求，但**都必须能搜到**
        Api plain = get("/api/stations/search", null);
        assertTrue(plain.isSuccess(), "不传坐标必须照常可用（定位被拒/老客户端）：" + plain);
        List<Long> plainIds = idsOf(plain.data());
        assertTrue(plainIds.contains(near) && plainIds.contains(far) && plainIds.contains(noCoord),
                "不传坐标时应返回全部候选：" + plainIds);

        // 传坐标：近的在前、带距离；没坐标的排最后但**仍在列表里**
        Api sorted = get("/api/stations/search?lat=36.6865&lng=117.0617", null);
        assertTrue(sorted.isSuccess(), "带坐标搜索应成功：" + sorted);
        List<Long> ids = idsOf(sorted.data());
        assertEquals(3, ids.size(), "三家都该在（没坐标的不许被丢掉）：" + ids);
        assertEquals(near, ids.get(0).longValue(), "最近的站要排第一：" + ids);
        assertEquals(far, ids.get(1).longValue(), "其次才是远站：" + ids);
        assertEquals(noCoord, ids.get(2).longValue(), "没坐标的排最后，但照样返回：" + ids);

        JsonNode nearNode = nodeOf(sorted.data(), near);
        assertNotNull(nearNode);
        assertFalse(nearNode.path("distanceKm").isNull(), "带坐标时每家都该算得出距离：" + nearNode);
        assertTrue(nearNode.path("distanceKm").asDouble() < 5,
                "近站距离应小于 5 公里，实际=" + nearNode.path("distanceKm"));

        JsonNode noCoordNode = nodeOf(sorted.data(), noCoord);
        assertTrue(noCoordNode.path("distanceKm").isNull(),
                "没设坐标 ⇒ distanceKm 为 null（前端显示「位置未设置」），而不是 0 或 -1：" + noCoordNode);
    }

    @Test
    @DisplayName("停业站与待上线站不出现在可申请列表里")
    void closedAndPendingStationsAreNotApplicable() {
        long open = stationAt("正常站", "36.686500", "117.061700");
        long closed = insert("INSERT INTO station(name, status, operating_status) VALUES (?, 2, 1)", "停业站");
        long pending = insert("INSERT INTO station(name, status, operating_status) VALUES (?, 1, 3)", "待上线站");

        Api res = get("/api/stations/search", null);
        assertTrue(res.isSuccess(), "搜索应可读：" + res);
        List<Long> ids = idsOf(res.data());
        assertTrue(ids.contains(open), "正常站必须在：" + ids);
        assertFalse(ids.contains(closed), "停业站（status=2）不该被申请：" + ids);
        assertFalse(ids.contains(pending), "待上线站（operating_status=3）不该被发现：" + ids);
    }
}
