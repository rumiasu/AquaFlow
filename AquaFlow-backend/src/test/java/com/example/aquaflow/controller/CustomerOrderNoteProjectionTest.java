package com.example.aquaflow.controller;

import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.service.OrderService;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerOrderNoteProjectionTest {
    private final OrderService orders = mock(OrderService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderController controller = new OrderController();
    CustomerOrderNoteProjectionTest() {
        ReflectionTestUtils.setField(controller, "orderService", orders);
        ReflectionTestUtils.setField(controller, "paymentService", payments);
    }
    @AfterEach void clear() { AuthContext.clear(); }
    private Orders source(String customerNote) {
        Orders o = new Orders(); o.setId(20L); o.setCustomerId(7L); o.setStationId(11L);
        o.setStatus(2); o.setPaymentMethod(2); o.setCustomerNote(customerNote);
        o.setSpecialNote("请放门口\n[退回站长] 内部私语\n[配送异常] 地址找不到（上报人ID=9）");
        o.setBarrelDiscrepancyNote("配送内部差异"); o.setCustomerRiskNote("站长内部评级");
        return o;
    }
    @Test void customerListAndDetailHideMixedNotesButRetainSnapshotAndTransferState() {
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        Orders original = source("请放门口");
        when(orders.list(null, 7L, null, null, null, 200, 0, false)).thenReturn(List.of(original));
        when(orders.getById(20L)).thenReturn(original); when(payments.canSelfPay(original)).thenReturn(true);
        for (Orders view : List.of(controller.list(null, null, null, null, null, null, null).getData().get(0), controller.getById(20L).getData())) {
            assertNotSame(original, view); assertEquals("请放门口", view.getSpecialNote());
            assertNull(ReflectionTestUtils.getField(view, "specialNote"), "底层字段也不得保留内部文本");
            assertEquals("STAFF", view.getTransferKind()); assertTrue(view.getTransferPending()); assertTrue(view.getCanRepay());
            var tree = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(view));
            assertEquals("请放门口", tree.path("specialNote").asString());
            assertFalse(tree.toString().contains("内部私语")); assertFalse(tree.toString().contains("配送内部差异"));
            assertFalse(tree.toString().contains("站长内部评级")); assertFalse(tree.toString().contains("上报人ID"));
        }
        assertTrue(original.getSpecialNote().contains("内部私语"), "不能改变员工读取的原实体");
    }
    @Test void mvcJacksonConverterDoesNotSerializeInternalFields() throws Exception {
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        Orders original = source("请放门口"); when(orders.getById(20L)).thenReturn(original);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/20"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var body = JsonMapper.builder().build().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("请放门口", body.path("data").path("specialNote").asString());
        assertEquals("STAFF", body.path("data").path("transferKind").asString());
        assertFalse(body.toString().contains("内部私语")); assertFalse(body.toString().contains("配送内部差异"));
        assertFalse(body.toString().contains("上报人ID"));
    }
    @Test void employeeMvcReadsPaidOrderAndRejectsPendingNonCashFixture() throws Exception {
        AuthContext.set(new AuthContext.AuthUser(9L, "staff", "STATION_MANAGER", 11L));
        Orders original = source("请放门口");
        original.setPaymentMethod(1); original.setPaymentStatus(1);
        when(orders.getById(20L)).thenReturn(original);
        assertFalse(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(original));
        assertThrows(com.example.aquaflow.exception.BusinessException.class, () -> controller.getById(20L));
        original.setPaymentStatus(2);
        assertTrue(com.example.aquaflow.util.OrderTaskAccess.visibleToEmployee(original));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/20"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var body = JsonMapper.builder().build().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, body.path("code").asInt());
        assertTrue(body.path("data").path("specialNote").asString().contains("内部私语"));
        assertEquals("配送内部差异", body.path("data").path("barrelDiscrepancyNote").asString());
    }
    @Test void unknownLegacyNoteIsNotGuessedAndEmployeeStillSeesIt() {
        Orders legacy = source(null); when(orders.getById(20L)).thenReturn(legacy);
        AuthContext.set(new AuthContext.AuthUser(7L, "customer", null, null));
        var customer = controller.getById(20L).getData();
        assertNull(customer.getSpecialNote()); assertEquals("STAFF", customer.getTransferKind());
        assertTrue(((com.example.aquaflow.vo.CustomerOrderView) customer).getLegacyNoteUnavailable());
        AuthContext.set(new AuthContext.AuthUser(9L, "staff", "STATION_MANAGER", 11L));
        var employee = controller.getById(20L).getData(); assertSame(legacy, employee);
        assertTrue(employee.getSpecialNote().contains("内部私语")); assertEquals("配送内部差异", employee.getBarrelDiscrepancyNote());
    }
}
