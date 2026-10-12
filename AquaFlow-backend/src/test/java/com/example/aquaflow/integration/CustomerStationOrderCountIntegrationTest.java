package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractScenarioTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("站长客户卡：本站订单数按订单归属站实时统计")
class CustomerStationOrderCountIntegrationTest extends AbstractScenarioTest {

    private static final int CASH = 2;

    private JsonNode rowById(JsonNode rows, long customerId) {
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == customerId) {
                return row;
            }
        }
        return null;
    }

    private int ordersOf(JsonNode rows, long customerId) {
        JsonNode row = rowById(rows, customerId);
        assertNotNull(row, "客户应仍是本站列表成员: " + customerId);
        return row.path("totalOrders").asInt(-1);
    }

    private JsonNode list(World station) {
        Api result = get("/api/customers", station.managerToken());
        assertEquals(0, result.code(), "本站客户列表应可读: " + result);
        return result.data();
    }

    @Test
    @DisplayName("只数本站未取消订单，外派仍属原站；旧计数 0/999 不改结果且纯取消客户仍在列表")
    void countsLiveOrdersByOriginalStationWithoutChangingMembership() {
        World owner = openStation("客户订单数归属站");
        World fulfillment = openStation("客户订单数履约站");
        long customerId = owner.customerId();

        createOrderFull(customerId, owner.addressId(), owner.stationId(), owner.productId(),
                1, 1, CASH, "20.00", "0.00", "20.00", false, 0);
        long dispatchedOrder = createOrderCrossStation(customerId, owner.addressId(),
                owner.stationId(), fulfillment.stationId(), owner.productId(),
                4, 2, CASH, "20.00", "0.00", "20.00");
        assertEquals(1, jdbc.update("UPDATE orders SET settle_station_id=? WHERE id=?",
                fulfillment.stationId(), dispatchedOrder), "造数：外派单营收结算站在履约站");
        assertEquals(owner.stationId(), longOf("SELECT station_id FROM orders WHERE id=?", dispatchedOrder),
                "外派不能改订单原归属站");
        assertEquals(fulfillment.stationId(), longOf(
                "SELECT delivery_station_id FROM orders WHERE id=?", dispatchedOrder));
        assertEquals(fulfillment.stationId(), longOf(
                "SELECT settle_station_id FROM orders WHERE id=?", dispatchedOrder));

        createOrderFull(customerId, owner.addressId(), fulfillment.stationId(), fulfillment.productId(),
                1, 1, CASH, "20.00", "0.00", "20.00", false, 0);
        createOrderFull(customerId, owner.addressId(), owner.stationId(), owner.productId(),
                5, 1, CASH, "20.00", "0.00", "20.00", false, 0);

        long canceledOnlyCustomer = createCustomer("仅有取消单的客户", "cancelled-only-customer");
        long canceledOnlyAddress = createAddress(canceledOnlyCustomer, "合成测试地址");
        createOrderFull(canceledOnlyCustomer, canceledOnlyAddress, owner.stationId(), owner.productId(),
                5, 1, CASH, "20.00", "0.00", "20.00", false, 0);

        assertEquals(1, jdbc.update("UPDATE customer SET total_orders=0 WHERE id=?", customerId));
        assertEquals(1, jdbc.update("UPDATE customer SET total_orders=999 WHERE id=?", canceledOnlyCustomer));

        JsonNode ownerRowsWithZero = list(owner);
        assertEquals(2, ownerRowsWithZero.size(), "订单驱动的列表成员与取消单客户必须保留");
        assertEquals(2, ordersOf(ownerRowsWithZero, customerId),
                "本站两笔未取消单应计 2，且不受遗留 0 影响");
        assertEquals(0, ordersOf(ownerRowsWithZero, canceledOnlyCustomer),
                "只有取消单的客户仍在列表，但本站订单数为 0");

        assertEquals(1, jdbc.update("UPDATE customer SET total_orders=999 WHERE id=?", customerId));
        JsonNode ownerRowsWithStaleHighValue = list(owner);
        assertEquals(2, ownerRowsWithStaleHighValue.size(), "更新遗留值不得改变客户列表成员");
        assertEquals(2, ordersOf(ownerRowsWithStaleHighValue, customerId),
                "遗留 999 不能污染本站两笔订单的实时计数");

        Api ownerDetail = get("/api/customers/" + customerId, owner.managerToken());
        assertEquals(0, ownerDetail.code(), "归属站客户详情应可读");
        assertEquals(2, ownerDetail.data().path("totalOrders").asInt(-1),
                "客户详情也用本站未取消订单数");
        Api ownerProfile = get("/api/customers/" + customerId + "/profile", owner.managerToken());
        assertEquals(0, ownerProfile.code(), "归属站详情画像应继续可读");
        assertEquals(0, ownerProfile.data().path("totalOrders").asInt(-1),
                "画像订单数仍只算结算站为归属站的已完成单");

        Api fulfillmentList = get("/api/customers?stationId=" + owner.stationId(), fulfillment.managerToken());
        assertEquals(0, fulfillmentList.code(), "履约站客户列表应成功");
        assertEquals(1, fulfillmentList.data().size(), "站长仍只能按登录态读取本站订单客户");
        assertEquals(1, ordersOf(fulfillmentList.data(), customerId),
                "同一客户在履约站只有一笔本站归属订单；外派单仍记原站");
        Api fulfillmentProfile = get("/api/customers/" + customerId + "/profile", fulfillment.managerToken());
        assertEquals(0, fulfillmentProfile.code(), "有本站归属订单的履约站可以读取客户画像");
        assertEquals(1, fulfillmentProfile.data().path("totalOrders").asInt(-1),
                "已完成外派单仍按独立的结算站口径计入画像");
    }
}
