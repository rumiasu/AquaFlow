package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 走真实 HTTP 与 MySQL 锁，证明未配置页和已有配置页都不能覆盖另一会话。 */
class DeliveryConfigConcurrencyIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH = "/api/manager/delivery-config";

    @Test
    void twoInitiallyUnconfiguredEditorsCannotBothCreate() throws Exception {
        long station = createUnconfiguredStation("并发首次配置");
        String token = manager(station);
        var before = get(PATH, token);
        assertFalse(before.data().path("configured").asBoolean());
        String snapshot = before.data().path("snapshot").asText();
        assertFalse(snapshot.isBlank());
        assertWinnerAndStoredFee(station, together(token, snapshot, "3", "7"));
        assertEquals(1, intOf("select count(*) from station_delivery_config where station_id=?", station));
        assertEquals(1, put(PATH, token, payload(snapshot, "0")).code(), "留在默认页的旧会话也必须拒绝");
    }

    @Test
    void twoExistingEditorsCannotBothReplaceTheSameSnapshot() throws Exception {
        long station = createStation("并发更新配置");
        String token = manager(station);
        String snapshot = get(PATH, token).data().path("snapshot").asText();
        assertWinnerAndStoredFee(station, together(token, snapshot, "4", "8"));
    }

    @Test
    void refreshRecoversAndSameValueSaveIsAllowed() {
        long station = createUnconfiguredStation("刷新后配置");
        String token = manager(station);
        String stale = get(PATH, token).data().path("snapshot").asText();
        assertEquals(0, put(PATH, token, payload(stale, "3")).code());
        assertEquals(1, put(PATH, token, payload(stale, "0")).code());
        String fresh = get(PATH, token).data().path("snapshot").asText();
        var saved = put(PATH, token, payload(fresh, "6"));
        assertEquals(0, saved.code());
        assertEquals(0, put(PATH, token, payload(saved.data().path("snapshot").asText(), "6.00")).code());
        assertEquals(0, decimalOf("select base_delivery_fee from station_delivery_config where station_id=?", station)
                .compareTo(new BigDecimal("6")));
    }

    @Test
    void missingSnapshotAndOtherStationSnapshotCannotBypassGuard() {
        long station = createUnconfiguredStation("配置本站");
        String token = manager(station);
        long otherStation = createUnconfiguredStation("配置他站");
        String otherToken = manager(otherStation);
        String otherSnapshot = get(PATH, otherToken).data().path("snapshot").asText();
        assertEquals(1, put(PATH, token, "{\"baseDeliveryFee\":9}").code());
        assertEquals(1, put(PATH, token, payload(otherSnapshot, "9")).code());
        assertEquals(0, intOf("select count(*) from station_delivery_config where station_id in (?,?)", station, otherStation));
    }

    @Test
    void customerAndDeliveryStaffStillCannotEditConfiguration() {
        long station = createStation("配置权限站");
        String snapshot = get(PATH, manager(station)).data().path("snapshot").asText();
        long delivery = createStaff("配置配送员", "DELIVERY", station, 1);
        long customer = createCustomer("配置客户", "config-permission-customer");
        for (String token : List.of(staffToken(delivery, "DELIVERY", station), customerToken(customer))) {
            assertNotEquals(0, get(PATH, token).code());
            assertNotEquals(0, put(PATH, token, payload(snapshot, "9")).code());
        }
        assertEquals(0, decimalOf("select base_delivery_fee from station_delivery_config where station_id=?", station)
                .compareTo(BigDecimal.ZERO));
    }

    private String manager(long station) {
        return staffToken(createStaff("配置站长", "STATION_MANAGER", station, 1), "STATION_MANAGER", station);
    }

    private String payload(String snapshot, String amount) {
        return "{\"expectedSnapshot\":\"" + snapshot + "\",\"baseDeliveryFee\":" + amount + "}";
    }

    private List<Api> together(String token, String snapshot, String first, String second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return put(PATH, token, payload(snapshot, first));
            });
            var b = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return put(PATH, token, payload(snapshot, second));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private void assertWinnerAndStoredFee(long station, List<Api> responses) {
        assertEquals(1, responses.stream().filter(r -> r.code() == 0).count(), responses.toString());
        assertEquals(1, responses.stream().filter(r -> r.code() == 1).count(), responses.toString());
        Api winner = responses.stream().filter(r -> r.code() == 0).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal(winner.data().path("config").path("baseDeliveryFee").asText())
                .compareTo(decimalOf("select base_delivery_fee from station_delivery_config where station_id=?", station)));
    }
}
