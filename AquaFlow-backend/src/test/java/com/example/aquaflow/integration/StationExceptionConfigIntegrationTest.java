package com.example.aquaflow.integration;

import com.example.aquaflow.mapper.StationExceptionConfigMapper;
import com.example.aquaflow.service.StationExceptionConfigService;
import com.example.aquaflow.service.impl.StationExceptionConfigServiceImpl;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点异常配置落库（{@code station_exception_config}）。
 *
 * <p>盯住的是一个真实存在过的缺口：该表 2026-09-11 就建好了，但
 * {@code StationExceptionConfigServiceImpl} 一直只写内存 Map，于是站长改完补偿优先级
 * <b>重启即静默回到默认值</b>——不报错、不打日志，只能靠"配置怎么又不生效了"来发现。</p>
 *
 * <p>因此断言分两层：① 值必须真的出现在表里；② 用<b>全新实例</b>读（模拟重启）。
 * 第②层是关键——只写内存的实现下，"同一个 bean 写进去再读出来一样"也会通过，
 * 那是个假绿的断言，挡不住这个缺陷。</p>
 */
class StationExceptionConfigIntegrationTest extends AbstractIntegrationTest {

    private static final String CONFIG_PATH = "/api/manager/exceptions/config";

    /** 「补偿优先级首位」在库中的原值，避免直接比对 JSON 文本（MySQL 会重排空白） */
    private static final String FIRST_PRIORITY_SQL =
            "select json_unquote(json_extract(compensation_priority, '$[0]')) "
                    + "from station_exception_config where station_id = ?";

    @Autowired
    private StationExceptionConfigMapper mapper;

    private String managerToken(long stationId) {
        long staffId = createStaff("站长", "STATION_MANAGER", stationId, 1);
        return staffToken(staffId, "STATION_MANAGER", stationId);
    }

    @Test
    @DisplayName("未配置过的站点返回默认值，且读操作不写库")
    void defaultsWhenNeverConfigured() {
        long stationId = createStation("S1");

        var res = get(CONFIG_PATH, managerToken(stationId));

        assertTrue(res.isSuccess(), "读配置应成功，实际=" + res);
        assertEquals("REFUND_TICKET", res.data().path("compensationPriority").path(0).asText(),
                "默认补偿优先级首位应为水票，实际=" + res);
        assertEquals(0, intOf("select count(*) from station_exception_config where station_id = ?", stationId),
                "GET 不该产生写操作（否则每次读都带上一把行锁和主从复制流量）");
    }

    @Test
    @DisplayName("保存后配置真的写进了 station_exception_config")
    void updatePersistsToDatabase() {
        long stationId = createStation("S1");

        var res = put(CONFIG_PATH, managerToken(stationId),
                "{\"compensationPriority\":[\"REFUND_CASH\",\"REFUND_TICKET\"]}");

        assertTrue(res.isSuccess(), "保存配置应成功，实际=" + res);
        assertEquals(1, intOf("select count(*) from station_exception_config where station_id = ?", stationId),
                "保存后表里应恰好有一行");
        assertEquals("REFUND_CASH",
                jdbc.queryForObject(FIRST_PRIORITY_SQL, String.class, stationId),
                "库中优先级首位应是被保存的值");
        assertEquals(1, intOf("select (auto_suggest_rules is not null and notify_templates is not null) "
                        + "from station_exception_config where station_id = ?", stationId),
                "只改一个键时，另两个键也必须落成完整可用的默认值——读方拿到 null 比拿到默认值危险得多");
    }

    @Test
    @DisplayName("重复保存是覆盖同一行，不会插出第二行")
    void repeatedUpdateOverwritesSameRow() {
        long stationId = createStation("S1");
        String token = managerToken(stationId);

        assertTrue(put(CONFIG_PATH, token, "{\"compensationPriority\":[\"REFUND_CASH\"]}").isSuccess());
        var second = put(CONFIG_PATH, token, "{\"compensationPriority\":[\"WAIVE_DEPOSIT\"]}");

        assertTrue(second.isSuccess(), "第二次保存应成功，实际=" + second);
        assertEquals(1, intOf("select count(*) from station_exception_config where station_id = ?", stationId),
                "一站一行：第二次保存必须走 ON DUPLICATE KEY UPDATE，而不是插出第二行");
        assertEquals("WAIVE_DEPOSIT", jdbc.queryForObject(FIRST_PRIORITY_SQL, String.class, stationId),
                "库中应是被后一次覆盖的值");
        assertEquals("WAIVE_DEPOSIT", get(CONFIG_PATH, token).data().path("compensationPriority").path(0).asText(),
                "再读回来也应是后一次的值");
    }

    @Test
    @DisplayName("全新实例（模拟重启）读到的仍是被保存的值")
    void savedValueVisibleToFreshInstance() {
        long stationId = createStation("S1");
        assertTrue(put(CONFIG_PATH, managerToken(stationId),
                "{\"compensationPriority\":[\"REFUND_CASH\"]}").isSuccess());

        // 手工 new 一个实现并把 Mapper 注入进去：它没有任何内存状态，
        // 若值还能读出来，来源只可能是数据库。纯内存实现下这里必然退回默认值。
        StationExceptionConfigService fresh = new StationExceptionConfigServiceImpl();
        ReflectionTestUtils.setField(fresh, "mapper", mapper);

        assertEquals(List.of("REFUND_CASH"), fresh.getConfig(stationId).get("compensationPriority"),
                "重启后仍应拿到站长保存的配置，而不是默认值");
    }

    @Test
    @DisplayName("配置按站隔离，两个站互不覆盖")
    void configIsolatedPerStation() {
        long s1 = createStation("S1");
        long s2 = createStation("S2");
        String t1 = managerToken(s1);
        String t2 = managerToken(s2);

        assertTrue(put(CONFIG_PATH, t1, "{\"compensationPriority\":[\"REFUND_CASH\"]}").isSuccess());
        assertTrue(put(CONFIG_PATH, t2, "{\"compensationPriority\":[\"WAIVE_DEPOSIT\"]}").isSuccess());

        assertEquals("REFUND_CASH", get(CONFIG_PATH, t1).data().path("compensationPriority").path(0).asText());
        assertEquals("WAIVE_DEPOSIT", get(CONFIG_PATH, t2).data().path("compensationPriority").path(0).asText());
        assertEquals(2, intOf("select count(*) from station_exception_config"),
                "两个站各占一行");
    }

    @Test
    @DisplayName("配送员与顾客都不得读写站点配置")
    void nonManagerRejected() {
        long stationId = createStation("S1");
        long deliveryId = createStaff("配送员", "DELIVERY", stationId, 1);
        long customerId = createCustomer("客户", "openid-config-authz");

        var byDelivery = put(CONFIG_PATH, staffToken(deliveryId, "DELIVERY", stationId),
                "{\"compensationPriority\":[\"REFUND_CASH\"]}");
        assertFalse(byDelivery.isSuccess(), "配送员不应能改站点配置，实际=" + byDelivery);
        assertTrue(byDelivery.message().contains("权限不足"), "应是权限拒绝，实际=" + byDelivery.message());

        var byCustomer = get(CONFIG_PATH, customerToken(customerId));
        assertFalse(byCustomer.isSuccess(), "顾客不应能读站点配置，实际=" + byCustomer);

        assertEquals(0, intOf("select count(*) from station_exception_config"),
                "被拒绝的请求不得留下任何配置行");
    }
}
