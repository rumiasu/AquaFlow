package com.example.aquaflow.service;

import com.example.aquaflow.dto.InventoryInboundDTO;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.InventoryServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No datasource or credentials: service command guards; SQL concurrency/rollback probe is separate. */
class InventoryInboundReceiptTest {
    private InventoryServiceImpl service;
    private InventoryMapper inventory;
    private ProductMapper products;
    private AuditLogMapper audit;
    private InventoryLedgerService ledger;
    private InventoryReservationService reservations;
    private final Map<String,List<String>> receipts = new HashMap<>();
    private static final String KEY = "INB-original-request-0001";

    @BeforeEach void setup() {
        receipts.clear(); service = new InventoryServiceImpl();
        inventory = mock(InventoryMapper.class); products = mock(ProductMapper.class); audit = mock(AuditLogMapper.class);
        ledger = mock(InventoryLedgerService.class); reservations = mock(InventoryReservationService.class);
        var stations = mock(StationMapper.class); when(stations.getByIdForUpdate(8L)).thenReturn(new Station());
        Product product = new Product(); product.setId(42L); when(products.getById(42L)).thenReturn(product);
        when(audit.inboundReceipts(eq(20L), anyString())).thenAnswer(a -> receipts.getOrDefault(a.getArgument(1), List.of()));
        var logs = mock(AuditLogService.class);
        doAnswer(a -> { receipts.put(a.getArgument(2, String.class), List.of(a.getArgument(3, String.class))); return null; })
                .when(logs).log(eq("INVENTORY_INBOUND"), eq("RECEIPT"), anyString(), anyString(), isNull());
        ReflectionTestUtils.setField(service,"inventoryMapper",inventory);
        ReflectionTestUtils.setField(service,"productMapper",products);
        ReflectionTestUtils.setField(service,"stationMapper",stations);
        ReflectionTestUtils.setField(service,"auditLogMapper",audit);
        ReflectionTestUtils.setField(service,"auditLogService",logs);
        ReflectionTestUtils.setField(service,"inventoryLedgerService",ledger);
        ReflectionTestUtils.setField(service,"inventoryReservationService",reservations);
        AuthContext.set(new AuthContext.AuthUser(20L,"staff","STATION_MANAGER",8L));
    }
    @AfterEach void cleanup() { AuthContext.clear(); }
    static List<InventoryInboundDTO.ItemDTO> items(int qty) {
        var item = new InventoryInboundDTO.ItemDTO(); item.setProductId(42L); item.setQuantity(qty); return List.of(item);
    }

    @Test void originalKeyReturnsReceiptWithoutAnotherStockLedgerOrBackfill() {
        var first = service.inboundWithReceipt(8L,items(3),KEY);
        var second = service.inboundWithReceipt(8L,items(3),KEY);
        assertEquals(first,second); assertEquals("APPLIED",second.get("status")); assertEquals(KEY,second.get("clientRequestId"));
        verify(inventory,times(1)).upsertQuantity(8L,42L,3);
        verify(ledger,times(1)).recordChange(eq(8L),eq(42L),eq(3),anyString(),isNull(),eq(20L),anyString());
        verify(reservations,times(1)).backfillReservations(8L,42L);
    }
    @Test void sameKeyChangedContentIsRejectedButGenuineNewKeyCanReceiveAnotherBatch() {
        service.inboundWithReceipt(8L,items(3),KEY);
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(4),KEY));
        verify(inventory,never()).upsertQuantity(8L,42L,4);
        service.inboundWithReceipt(8L,items(3),"INB-different-request-0002");
        verify(inventory,times(2)).upsertQuantity(8L,42L,3);
    }
    @Test void existingReceiptDoesNotRecreateAfterProductWasRemovedOrChanged() {
        service.inboundWithReceipt(8L,items(3),KEY); when(products.getById(42L)).thenReturn(null);
        assertEquals("APPLIED",service.inboundWithReceipt(8L,items(3),KEY).get("status"));
        verify(inventory,times(1)).upsertQuantity(8L,42L,3);
    }
    @Test void corruptOrDuplicatedReceiptFailsClosedWithoutAnotherIncrement() {
        service.inboundWithReceipt(8L,items(3),KEY); String target = receipts.keySet().iterator().next();
        String valid = receipts.get(target).get(0); receipts.put(target,List.of("corrupt"));
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(3),KEY));
        receipts.put(target,List.of(valid,valid));
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(3),KEY));
        verify(inventory,times(1)).upsertQuantity(8L,42L,3);
    }
    @Test void wrongStationOrCustomerIdentityCannotDispatch() {
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(9L,items(3),KEY));
        AuthContext.set(new AuthContext.AuthUser(20L,"customer","STATION_MANAGER",8L));
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(3),KEY));
        verifyNoInteractions(inventory);
    }
    @Test void anotherStationsCustomProductIsRejectedOnBothNewAndLegacyPaths() {
        Product foreign = new Product(); foreign.setOwnerStationId(9L); when(products.getById(42L)).thenReturn(foreign);
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(3),KEY));
        assertThrows(BusinessException.class,()->service.inbound(8L,items(3)));
        verifyNoInteractions(inventory,ledger,reservations);
    }
    @Test void invalidOriginalKeyDuplicateProductsAndEmptyItemsDoNotWrite() {
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,items(3),"bad"));
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,List.of(),KEY));
        assertThrows(BusinessException.class,()->service.inboundWithReceipt(8L,List.of(items(3).get(0),items(3).get(0)),KEY));
        verifyNoInteractions(inventory);
    }
}
