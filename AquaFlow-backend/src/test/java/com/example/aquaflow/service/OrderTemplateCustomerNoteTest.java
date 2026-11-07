package com.example.aquaflow.service;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.OrderTemplateServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderTemplateCustomerNoteTest {
    @Test void fromOrderUsesOnlyVerifiedCustomerSnapshotAndLeavesLegacyUnknown() {
        var service = new OrderTemplateServiceImpl(); var templates = mock(OrderTemplateMapper.class);
        var items = mock(OrderItemMapper.class); var orders = mock(OrderMapper.class);
        ReflectionTestUtils.setField(service, "templateMapper", templates); ReflectionTestUtils.setField(service, "orderItemMapper", items); ReflectionTestUtils.setField(service, "orderMapper", orders);
        Orders order = new Orders(); order.setCustomerId(7L); order.setSpecialNote("客户文字\n[配送备注] 内部内容");
        when(orders.getById(20L)).thenReturn(order); when(items.listByOrderId(20L)).thenReturn(List.of());
        order.setCustomerNote("客户文字"); assertEquals("客户文字", service.setFromOrder(7L, 20L, 11L).getSpecialNote());
        order.setCustomerNote(null); assertNull(service.setFromOrder(7L, 20L, 11L).getSpecialNote());
        assertThrows(RuntimeException.class, () -> service.setFromOrder(8L, 20L, 11L));
        verify(templates, times(2)).insert(any());
    }
}
