package com.example.aquaflow.service;

import com.example.aquaflow.constant.PendingItem;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.impl.DeliveryConsoleServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerTransferProjectionTest {
    @ParameterizedTest
    @ValueSource(strings = {"POOL", "REJECT_POOL", "POOL_DIRECTED", "DIRECTED_POOL"})
    void directedIncomingUsesLatestDispatchKindInsteadOfEveryForeignOrder(String route) throws Exception {
        String pool = com.example.aquaflow.constant.DispatchKind.NOTE_POOL;
        String directed = com.example.aquaflow.constant.DispatchKind.NOTE_DIRECTED + "8";
        Orders row = row(4L, null);
        row.setSpecialNote(switch (route) {
            case "REJECT_POOL" -> com.example.aquaflow.constant.DispatchKind.NOTE_POOL_BY_REJECT;
            case "POOL_DIRECTED" -> pool + "\n" + directed;
            case "DIRECTED_POOL" -> directed + "\n" + pool;
            default -> pool;
        });
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.listDirectedIncoming(8L)).thenReturn(List.of(row));
        DeliveryConsoleServiceImpl service = new DeliveryConsoleServiceImpl();
        for (var field : DeliveryConsoleServiceImpl.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
        }
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        List<Orders> actual = service.listDirectedIncoming(8L);
        assertEquals(route.equals("POOL_DIRECTED") ? 1 : 0, actual.size());
        if (!actual.isEmpty()) assertNull(actual.get(0).getCustomerName());
    }
    @Test void colleagueTransferNeverAppearsInDailyManagerQueues() {
        Orders transfer = row(1L, "TRANSFER");
        Orders returned = row(2L, "RETURN_STATION");
        Orders cancelled = row(3L, "CANCEL_REQUEST");
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.listTransferredOrders(8L)).thenReturn(List.of(transfer, returned, cancelled));
        DeliveryConsoleServiceImpl service = new DeliveryConsoleServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        OrderItemMapper items = mock(OrderItemMapper.class);
        when(items.listSummaryByOrderIds(List.of(2L, 3L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "orderItemMapper", items);
        assertEquals(List.of(2L, 3L), service.listStationTransferred(8L).stream().map(Orders::getId).toList());
        assertFalse(PendingItem.STAFF_TRANSFER_SUB_KINDS.contains("TRANSFER"));
        List<?> station = (List<?>) service.pendingApprovals(8L).get("station");
        assertEquals(2, station.size());
        assertNull(returned.getCustomerName());
        assertNull(returned.getCustomerPhone());
        assertEquals("本单收货人", returned.getReceiverName());
        verify(items, times(2)).listSummaryByOrderIds(List.of(2L, 3L));
    }
    private Orders row(long id, String subKind) {
        Orders o = new Orders(); o.setId(id); o.setStationId(7L); o.setDeliveryStationId(8L);
        o.setTransferPendingSubKind(subKind); o.setCustomerName("归属站客户档案"); o.setCustomerPhone("13800000000");
        o.setReceiverName("本单收货人"); return o;
    }
}
