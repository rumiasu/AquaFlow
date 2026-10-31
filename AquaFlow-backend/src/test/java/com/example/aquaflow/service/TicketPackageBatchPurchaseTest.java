package com.example.aquaflow.service;

import com.example.aquaflow.controller.TicketAccountController;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.impl.TicketAccountServiceImpl;
import com.example.aquaflow.service.impl.TicketTierService;
import com.example.aquaflow.util.TicketPurchaseIntent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real purchase orchestration/receipt projection, with no JDBC or channel writes. */
class TicketPackageBatchPurchaseTest {
    TicketAccountServiceImpl service;
    Map<Class<?>,Object> mocks;
    Product product; Inventory inventory; TicketPackage pack;
    @SuppressWarnings("unchecked") <T> T dep(Class<T> type) { return (T)mocks.get(type); }
    @BeforeEach void setup() throws Exception {
        mocks = new HashMap<>(); service = new TicketAccountServiceImpl();
        for (Field f : TicketAccountServiceImpl.class.getDeclaredFields()) if (f.isAnnotationPresent(Autowired.class)) {
            Object mock = mocks.computeIfAbsent(f.getType(), type -> mock(type));
            f.setAccessible(true); f.set(service, mock);
        }
        Station station = new Station(); station.setId(1L); station.setStatus(1);
        when(dep(StationMapper.class).getById(1L)).thenReturn(station);
        product = new Product(); product.setId(5L); product.setStatus(1); product.setCategory(2);
        when(dep(ProductMapper.class).getById(5L)).thenReturn(product);
        inventory = new Inventory(); inventory.setStationId(1L); inventory.setProductId(5L);
        inventory.setEnabled(1); inventory.setTicketEnabled(1); inventory.setTicketPrice(new BigDecimal("8.00"));
        when(dep(InventoryMapper.class).getByStationAndProduct(1L,5L)).thenReturn(inventory);
        when(dep(TicketTierService.class).usesCustomTicket(product,inventory)).thenReturn(true);
        pack = new TicketPackage(); pack.setId(77L); pack.setStationId(1L); pack.setProductId(5L);
        pack.setQty(10); pack.setPrice(new BigDecimal("19.95")); pack.setUnitPrice(new BigDecimal("2.00")); pack.setStatus(1);
        when(dep(TicketPackageMapper.class).getById(77L)).thenReturn(pack);
    }
    PaymentRecord buy(int qty, Long id, Integer unified) { return service.purchaseTicket(7L,5L,qty,2,1L,"batch-key",id,unified); }
    void noPayment() { verify(dep(PaymentRecordMapper.class),never()).insert(any()); }

    @Test void customCopiesUseExactPackageTotalAndOriginalDigest() {
        PaymentRecord record = buy(30,77L,null);
        assertEquals(new BigDecimal("59.85"),record.getAmount()); assertEquals(30,record.getTicketQty());
        assertEquals(77L,record.getTicketPackageId()); assertEquals(1,record.getStatus());
        assertEquals(TicketPurchaseIntent.digest(7L,1L,5L,30,2,77L,null),record.getPurchaseRequestDigest());
        verify(dep(PaymentRecordMapper.class)).insert(record);
    }
    @Test void rejectsNonWholeZeroNegativeAndExcessiveCountsWithoutPayment() {
        for (int qty : new int[]{0,-1,11,5001,Integer.MAX_VALUE}) assertThrows(BusinessException.class,()->buy(qty,77L,null));
        noPayment();
    }
    @Test void rejectsInvalidCrossStationOrUnavailableTierWithoutPayment() {
        pack.setStationId(2L); assertThrows(BusinessException.class,()->buy(20,77L,null));
        pack.setStationId(1L); pack.setStatus(0); assertThrows(BusinessException.class,()->buy(20,77L,null));
        pack.setStatus(1); pack.setQty(0); assertThrows(BusinessException.class,()->buy(20,77L,null)); noPayment();
    }
    @Test void rejectsNullZeroAndOverflowMoneyWithoutPayment() {
        pack.setPrice(null); assertThrows(BusinessException.class,()->buy(20,77L,null));
        pack.setPrice(BigDecimal.ZERO); assertThrows(BusinessException.class,()->buy(20,77L,null));
        pack.setPrice(new BigDecimal("99999999.99")); assertThrows(BusinessException.class,()->buy(20,77L,null)); noPayment();
    }
    @Test void unifiedCopiesReturnSingleTierInDirectAndRecoveredReceipt() {
        product.setCategory(1); when(dep(TicketTierService.class).usesCustomTicket(product,inventory)).thenReturn(false);
        when(dep(TicketTierService.class).unifiedConfigured(1L)).thenReturn(true);
        StationTicketDiscount tier = new StationTicketDiscount(); tier.setQty(10);
        when(dep(TicketTierService.class).requireUnifiedTier(1L,10)).thenReturn(tier);
        when(dep(TicketTierService.class).priceUnifiedTier(product,inventory,tier)).thenReturn(Map.of("unitPrice",new BigDecimal("1.75"),"totalPrice",new BigDecimal("17.50")));
        PaymentRecord record = buy(20,null,10); record.setId(91L);
        assertEquals(new BigDecimal("35.00"),record.getAmount()); assertEquals(10,TicketPurchaseIntent.originalUnifiedQty(record));
        Map<?,?> response = ReflectionTestUtils.invokeMethod(new TicketAccountController(),"purchaseResultOf",record);
        assertEquals(20,response.get("quantity")); assertEquals(10,response.get("unifiedQty"));
        when(dep(PaymentRecordMapper.class).getByCustomerAndIdempotencyKeyForUpdate(7L,"batch-key")).thenReturn(record);
        assertSame(record,buy(20,null,10)); assertThrows(BusinessException.class,()->buy(30,null,10));
        verify(dep(PaymentRecordMapper.class),times(1)).insert(any());
    }
    @Test void legacySingleTierPriceIsAuthoritativeWithoutChangingRequestIdentity() {
        pack.setQty(1); pack.setPrice(new BigDecimal("7.25"));
        when(dep(TicketPackageMapper.class).getByStationProductQty(1L,5L,1)).thenReturn(pack);
        PaymentRecord record = buy(3,null,null);
        assertEquals(new BigDecimal("21.75"),record.getAmount()); assertNull(record.getTicketPackageId());
        when(dep(PaymentRecordMapper.class).getByCustomerAndIdempotencyKeyForUpdate(7L,"batch-key")).thenReturn(record);
        assertSame(record,buy(3,null,null)); assertThrows(BusinessException.class,()->buy(4,null,null));
    }
    @Test void disabledSingleTierAndMissingRetailPriceCannotFallBackToWaterPrice() {
        pack.setQty(1); pack.setStatus(0);
        when(dep(TicketPackageMapper.class).getByStationProductQty(1L,5L,1)).thenReturn(pack);
        assertThrows(BusinessException.class,()->buy(1,null,null));
        when(dep(TicketPackageMapper.class).getByStationProductQty(1L,5L,1)).thenReturn(null);
        inventory.setTicketPrice(BigDecimal.ZERO); product.setPrice(new BigDecimal("100.00"));
        assertThrows(BusinessException.class,()->buy(1,null,null)); noPayment();
    }
    @Test void malformedUnifiedNotesAreNeverGuessedFromCurrentConfiguration() {
        PaymentRecord record = new PaymentRecord(); record.setTicketQty(20);
        for (String note : List.of("线上购买水票","线上购买水票（站级统一折扣 0 张档）","线上购买水票（站级统一折扣 30 张档）")) {
            record.setNote(note); assertNull(TicketPurchaseIntent.originalUnifiedQty(record));
        }
    }
}
