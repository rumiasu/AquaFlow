package com.example.aquaflow.integration;

import com.example.aquaflow.mapper.RecordDisplayNameMapper;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 只在基类保护的独立测试库执行；实际MySQL验证新名称查询的站别、快照及删除兜底。 */
class RecordDisplayNameMapperIntegrationTest extends AbstractIntegrationTest {
    @Autowired RecordDisplayNameMapper names;

    private long record(long station, long customer, long product, int type) {
        return insert("insert into barrel_record(station_id,customer_id,product_id,type,quantity,status) values(?,?,?,?,2,1)",
                station, customer, product, type);
    }
    private long exception(long station, long customer, long order) {
        return insert("insert into order_barrel_exception(station_id,customer_id,order_id,category) values(?,?,?,'RETURN_SHORT')",
                station, customer, order);
    }

    @Test void crossStationExceptionKeepsReceiverSnapshotButNeverOtherStationProfile() {
        long owner = createStation("归属站"), delivery = createStation("履约站"), customer = createCustomer("客户档案甲", "name-scope-a");
        long product = createProduct("商品甲", 1, "20", "50", 0, "0");
        long address = createAddress(customer, "合成测试地址");
        long order = createOrderCrossStation(customer, address, owner, delivery, product, 2, 2, 1, "20", "0", "20");
        jdbc.update("update orders set receiver_name='订单收件人乙' where id=?", order);
        createCustomerStationConfig(customer, delivery, 0); // 即使另有绑定，该跨站行也不能解除画像屏蔽。
        long own = exception(owner, customer, order), cross = exception(delivery, customer, order);
        var ownName = names.exceptionNames(owner, List.of(own)).get(0);
        var crossName = names.exceptionNames(delivery, List.of(cross)).get(0);
        assertEquals("客户档案甲", ownName.getCustomerName());
        assertNull(crossName.getCustomerName()); assertEquals("订单收件人乙", crossName.getReceiverName());
        assertTrue(names.exceptionNames(delivery, List.of(own)).isEmpty());
        long manager = createStaff("履约站长", "STATION_MANAGER", delivery, 1);
        var response = get("/api/manager/exceptions", staffToken(manager, "STATION_MANAGER", delivery));
        assertEquals(0, response.code());
        var received = response.data().path("records").get(0);
        assertFalse(received.hasNonNull("customerName")); assertEquals("订单收件人乙", received.path("receiverName").asText());
    }

    @Test void returnNamesRequireVisibleCustomerAndProductButKeepRetiredOwnProducts() {
        long station = createStation("办理站"), other = createStation("他站"), customer = createCustomer("已绑定客户", "name-scope-b");
        createCustomerStationConfig(customer, station, 0);
        long own = createProduct("本站历史商品", 1, "20", "50", 0, "0");
        jdbc.update("update product set owner_station_id=?,status=0 where id=?", station, own);
        long privateOther = createProduct("他站私有商品", 1, "20", "50", 0, "0");
        jdbc.update("update product set owner_station_id=? where id=?", other, privateOther);
        long record = record(station, customer, own, 2), foreignProduct = record(station, customer, privateOther, 2);
        var visible = names.barrelNames(station, List.of(record)).get(0);
        assertEquals("已绑定客户", visible.getCustomerName()); assertEquals("本站历史商品", visible.getProductName());
        assertEquals("办理站", visible.getStationName());
        assertNull(names.barrelNames(station, List.of(foreignProduct)).get(0).getProductName());
        assertTrue(names.barrelNames(other, List.of(record)).isEmpty());
    }

    @Test void missingHistoricalReferencesAndEmptyBatchesNeverBorrowOrEnumerateNames() {
        long station = createStation("历史办理站");
        long missing = record(station, 990001, 990002, 2), delivery = record(station, 990001, 990002, 8);
        var row = names.barrelNames(station, List.of(missing)).get(0);
        assertEquals(missing, row.getId()); assertNull(row.getCustomerName()); assertNull(row.getProductName());
        assertTrue(names.barrelNames(station, List.of(delivery)).isEmpty());
        assertTrue(names.barrelNames(station, List.of()).isEmpty()); assertTrue(names.barrelNames(station, null).isEmpty());
        assertTrue(names.exceptionNames(station, List.of()).isEmpty()); assertTrue(names.exceptionNames(station, null).isEmpty());
    }
}
