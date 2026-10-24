package com.example.aquaflow.controller;

import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC JSON binding, synthetic service; never connects to a datasource. */
class InventoryInboundControllerTest {
    @AfterEach void clear() { AuthContext.clear(); }
    @Test void keyedBodyReachesCommandAndReturnsMatchingReceipt() throws Exception {
        var service = mock(InventoryService.class);
        var item = new com.example.aquaflow.dto.InventoryInboundDTO.ItemDTO(); item.setProductId(42L); item.setQuantity(3);
        when(service.inboundWithReceipt(eq(8L),anyList(),eq("INB-original-request-0001")))
                .thenReturn(Map.of("clientRequestId","INB-original-request-0001","stationId",8,"status","APPLIED","items",java.util.List.of(item)));
        var controller = new InventoryController(); ReflectionTestUtils.setField(controller,"inventoryService",service);
        AuthContext.set(new AuthContext.AuthUser(20L,"staff","STATION_MANAGER",8L));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/inventory/inbound-with-receipt?stationId=8")
                .contentType("application/json").content("{\"clientRequestId\":\"INB-original-request-0001\",\"items\":[{\"productId\":42,\"quantity\":3}]}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.clientRequestId").value("INB-original-request-0001"))
                .andExpect(jsonPath("$.data.stationId").value(8))
                .andExpect(jsonPath("$.data.items[0].productId").value(42))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3));
        verify(service).inboundWithReceipt(eq(8L),argThat(items->items.size()==1&&items.get(0).getQuantity()==3),eq("INB-original-request-0001"));
        verify(service,never()).inbound(anyLong(),anyList());
    }
    @Test void oldBackendWithoutReceiptRouteCannotAccidentallyApplyTheKeyedCommand() throws Exception {
        var legacy = new LegacyInboundController(); var mvc = MockMvcBuilders.standaloneSetup(legacy).build();
        mvc.perform(post("/api/inventory/inbound-with-receipt?stationId=8")
                .contentType("application/json").content("{\"clientRequestId\":\"INB-original-request-0001\",\"items\":[{\"productId\":42,\"quantity\":3}]}"))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0, legacy.calls);
        mvc.perform(post("/api/inventory/inbound?stationId=8")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, legacy.calls);
    }
    @org.springframework.web.bind.annotation.RestController
    static class LegacyInboundController {
        int calls;
        @org.springframework.web.bind.annotation.PostMapping("/api/inventory/inbound")
        public com.example.aquaflow.common.Result<Void> inbound() { calls++; return com.example.aquaflow.common.Result.success(); }
    }
    @Test void legacyBodyKeepsOriginalApiWhileWrongStationDoesNotCallEitherCommand() throws Exception {
        var service = mock(InventoryService.class); var controller = new InventoryController();
        ReflectionTestUtils.setField(controller,"inventoryService",service);
        AuthContext.set(new AuthContext.AuthUser(20L,"staff","STATION_MANAGER",8L));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/inventory/inbound?stationId=8").contentType("application/json").content("{\"items\":[{\"productId\":42,\"quantity\":3}]}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(service).inbound(eq(8L),anyList()); clearInvocations(service);
        mvc.perform(post("/api/inventory/inbound?stationId=9").contentType("application/json").content("{\"clientRequestId\":\"INB-original-request-0001\",\"items\":[{\"productId\":42,\"quantity\":3}]}"))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(service);
    }
}
