package com.example.aquaflow.service;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.impl.DeliveryConsoleServiceImpl;
import com.example.aquaflow.util.CosUtil;
import com.example.aquaflow.util.ProductImageResolver;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Controlled service and mapper-binding checks only: no Spring context, database, or credentials. */
class DeliveryListItemSummaryTest {
    @Test
    void historyBatchesVisibleOrdersAndPreservesSnapshotsAndProfileMask() {
        Orders cross = order(2L, 1L, 2L), local = order(1L, 2L, 2L);
        List<Orders> rows = new ArrayList<>(List.of(cross, local));
        OrderItem water = item(21L, 2L, 3, 1, "/images/water.png");
        OrderItem bottle = item(22L, 2L, 1, 2, null);
        OrderItem machine = item(23L, 2L, 1, 3, "unavailable-image-key");
        OrderItem unknown = item(11L, 1L, 2, null, null);
        AtomicInteger batches = new AtomicInteger();
        DeliveryConsoleServiceImpl service = service(rows, List.of(unknown, water, bottle, machine), List.of(2L, 1L), batches);

        List<Orders> result = service.listHistory(8L);

        assertSame(rows, result);
        assertEquals(List.of(2L, 1L), result.stream().map(Orders::getId).toList());
        assertEquals(1, batches.get());
        assertEquals(List.of(3, 1, 1), cross.getItems().stream().map(OrderItem::getQuantity).toList());
        assertEquals("snapshot-21", cross.getItems().get(0).getProductNameSnapshot());
        assertEquals("18.9L", cross.getItems().get(0).getSpecSnapshot());
        assertEquals("/images/water.png", water.getImageUrl());
        assertNull(machine.getImageUrl());
        assertNull(unknown.getCategory());
        assertNull(cross.getCustomerName()); assertNull(cross.getCustomerPhone());
        assertEquals("receiver", cross.getReceiverName()); assertEquals("snapshot-phone", cross.getReceiverPhone());
        assertEquals("profile", local.getCustomerName());
        assertEquals(OrderStatus.COMPLETED, cross.getStatus());
    }

    @Test
    void transfersBatchAfterOriginalSubkindFilterAndKeepMaskAndState() {
        Orders allowed = order(3L, 1L, 2L), filtered = order(4L, 1L, 2L);
        allowed.setTransferPendingSubKind(OrderTransfer.SUB_RETURN_STATION);
        filtered.setTransferPendingSubKind(OrderTransfer.SUB_TRANSFER);
        AtomicInteger batches = new AtomicInteger();
        DeliveryConsoleServiceImpl service = service(List.of(allowed, filtered), List.of(item(31L, 3L, 1, 2, null)), List.of(3L), batches);

        List<Orders> result = service.listStationTransferred(2L);

        assertEquals(List.of(3L), result.stream().map(Orders::getId).toList());
        assertEquals(1, batches.get()); assertNull(allowed.getCustomerName());
        assertEquals("receiver", allowed.getReceiverName());
        assertEquals(OrderTransfer.SUB_RETURN_STATION, allowed.getTransferPendingSubKind());
        assertEquals(1, allowed.getItems().size()); assertNull(filtered.getItems());
    }

    @Test
    void emptyListsSkipBatchAndMissingItemsRemainAnEmptyList() {
        AtomicInteger batches = new AtomicInteger();
        assertTrue(service(List.of(), List.of(), List.of(), batches).listHistory(8L).isEmpty());
        assertEquals(0, batches.get());
        Orders old = order(9L, 2L, 2L);
        assertTrue(service(List.of(old), List.of(), List.of(9L), batches).listHistory(8L).get(0).getItems().isEmpty());
        assertEquals(1, batches.get());
    }

    @Test
    void mapperBindsOneOrderedBatchWithLeftJoinAndSnapshotColumns() {
        Configuration config = new Configuration(); config.addMapper(OrderItemMapper.class);
        BoundSql sql = config.getMappedStatement(OrderItemMapper.class.getName() + ".listSummaryByOrderIds")
                .getBoundSql(Map.of("orderIds", List.of(8L, 9L)));
        String text = sql.getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(text.contains("left join product p on p.id = oi.product_id"));
        assertTrue(text.contains("oi.product_name_snapshot")); assertTrue(text.contains("oi.spec_snapshot"));
        assertTrue(text.contains("p.category, p.image_object_name"));
        assertTrue(text.contains("order by oi.order_id, oi.id"));
        assertEquals(2, sql.getParameterMappings().size());
        assertFalse(text.contains("price")); assertFalse(text.contains("customer"));
    }

    private DeliveryConsoleServiceImpl service(List<Orders> rows, List<OrderItem> items, List<Long> expectedIds, AtomicInteger batches) {
        DeliveryConsoleServiceImpl service = new DeliveryConsoleServiceImpl();
        OrderMapper orders = proxy(OrderMapper.class, (method, args) -> {
            if (method.equals("listHistoryByDeliveryStaffId")) {
                assertEquals(8L, args[0]); assertEquals(OrderStatus.COMPLETED, args[1]); return rows;
            }
            if (method.equals("listTransferredOrders")) { assertEquals(2L, args[0]); return rows; }
            throw new AssertionError("Unexpected order read: " + method);
        });
        OrderItemMapper details = proxy(OrderItemMapper.class, (method, args) -> {
            assertEquals("listSummaryByOrderIds", method); assertEquals(expectedIds, args[0]);
            batches.incrementAndGet(); return items;
        });
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderItemMapper", details);
        ReflectionTestUtils.setField(service, "productImageResolver", new ProductImageResolver((CosUtil) null));
        return service;
    }

    private Orders order(Long id, Long owner, Long delivery) {
        Orders order = new Orders(); order.setId(id); order.setStationId(owner); order.setDeliveryStationId(delivery);
        order.setCustomerName("profile"); order.setCustomerPhone("profile-phone");
        order.setReceiverName("receiver"); order.setReceiverPhone("snapshot-phone"); order.setStatus(OrderStatus.COMPLETED);
        return order;
    }

    private OrderItem item(Long id, Long orderId, Integer quantity, Integer category, String image) {
        OrderItem item = new OrderItem(); item.setId(id); item.setOrderId(orderId); item.setQuantity(quantity);
        item.setProductNameSnapshot("snapshot-" + id); item.setSpecSnapshot("18.9L"); item.setCategory(category); item.setImageObjectName(image);
        return item;
    }

    private interface Invocation { Object call(String name, Object[] args); }
    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> call.call(method.getName(), args));
    }
}
