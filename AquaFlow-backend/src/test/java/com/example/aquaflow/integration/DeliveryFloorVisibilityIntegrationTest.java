package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送员能不能看到「楼层 / 电梯」（2026-09-17）。
 *
 * <p>P0-2 给 {@code address} 加了 {@code floor} / {@code has_elevator}，但此前只有计价在用：
 * 向客户收楼层费、给配送员补楼层补贴 —— <b>真正要爬楼的那个人看不到</b>。
 * 本用例把"配送员的订单详情必须带上这两个字段"钉住。</p>
 *
 * <p>两条口径一起钉：</p>
 * <ol>
 *   <li><b>三态不能塌成两态</b>：{@code has_elevator} 的 {@code null}（客户没确认过）
 *       与 {@code 0}（确认无电梯）是两件事。把 null 说成"无电梯"既误导配送员，
 *       也与后端收费口径相反（拿不准时后端不收楼层费）。</li>
 *   <li><b>取当前地址、不是下单快照</b>：配送员要知道"客户现在在哪层"。
 *       {@code orders} 只快照了地址文本与经纬度，<b>没有快照楼层</b>，这里也不该加 ——
 *       计费用的历史口径已经落在 {@code orders.floor_fee} 上。</li>
 * </ol>
 */
@DisplayName("配送员可见性 · 楼层 / 电梯（三态 + 取当前地址）")
class DeliveryFloorVisibilityIntegrationTest extends AbstractIntegrationTest {

    private Api detail(String token, long orderId) {
        return get("/api/delivery/orders/" + orderId, token);
    }

    /** 最小可用的订单：无桶、无押金，只为验证详情响应里带上地址的楼层字段。 */
    private long simpleOrder(long customer, long address, long station, long product) {
        long order = createOrderFull(customer, address, station, product,
                1 /* 待配送 */, 1 /* 待收款 */, 2 /* 现金 */,
                "20.00", "0.00", "20.00", false, 0);
        createOrderItem(order, product, "对账水 18.9L", 1, "20.00", "0.00", 1);
        return order;
    }

    @Test
    @DisplayName("订单详情带上楼层的三态值：确认无电梯=0、确认有电梯=1、没确认过=null（不能塌成 0）")
    void orderDetailCarriesElevatorTriState() {
        long station = createStation("楼层站");
        long manager = createStaff("楼层站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("楼层客户", "floor-openid");
        long address = createAddress(customer, "楼层小区 1 号");
        long product = createProduct("楼层水", 1, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        long order = simpleOrder(customer, address, station, product);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        // 客户确认过"无电梯" → 必须如实下发 0
        jdbc.update("UPDATE address SET floor = 6, has_elevator = 0 WHERE id = ?", address);
        Api a = detail(mgr, order);
        assertEquals(0, a.code(), "读配送订单详情: " + a);
        assertEquals(6, a.data().path("addressFloor").asInt(), "楼层应下发: " + a);
        assertEquals(0, a.data().path("addressHasElevator").asInt(), "无电梯应下发 0: " + a);

        // 确认过"有电梯" → 1
        jdbc.update("UPDATE address SET has_elevator = 1 WHERE id = ?", address);
        assertEquals(1, detail(mgr, order).data().path("addressHasElevator").asInt());

        // 没确认过 → **必须是 null，不能塌成 0**（否则等于替客户回答"无电梯"）
        jdbc.update("UPDATE address SET floor = NULL, has_elevator = NULL WHERE id = ?", address);
        Api c = detail(mgr, order);
        assertTrue(c.data().path("addressFloor").isNull(), "未填楼层应下发 null: " + c);
        assertTrue(c.data().path("addressHasElevator").isNull(),
                "未确认电梯应下发 null（三态不能塌成两态）: " + c);
    }

    @Test
    @DisplayName("楼层取当前地址而不是下单快照：客户改了楼层，配送员看到的是新的")
    void floorFollowsCurrentAddressNotSnapshot() {
        long station = createStation("楼层站2");
        long manager = createStaff("楼层站长2", "STATION_MANAGER", station, 1);
        long customer = createCustomer("楼层客户2", "floor-openid2");
        long address = createAddress(customer, "楼层小区 2 号");
        long product = createProduct("楼层水2", 1, "20.00", "0.00", 0, "0.00");
        createInventoryFull(station, product, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        jdbc.update("UPDATE address SET floor = 3, has_elevator = 0 WHERE id = ?", address);
        long order = simpleOrder(customer, address, station, product);
        assertEquals(3, detail(mgr, order).data().path("addressFloor").asInt());

        // 客户后来搬到了 12 楼：配送员要送到的是"现在"的 12 楼，不是下单时的 3 楼
        jdbc.update("UPDATE address SET floor = 12 WHERE id = ?", address);
        assertEquals(12, detail(mgr, order).data().path("addressFloor").asInt(),
                "配送员看到的是当前地址的楼层（客户现在在哪层才是他要的信息）");
    }
}
