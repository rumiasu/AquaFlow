package com.example.aquaflow.service;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.service.impl.TicketAccountServiceImpl;
import com.example.aquaflow.service.impl.TicketTierService;
import com.example.aquaflow.util.TicketPurchaseIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the real purchase service without Spring/JDBC; HTTP/transaction checks remain separate. */
class ClosedStationTicketPurchaseTest {
    private TicketAccountServiceImpl service;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private StationMapper stations;
    private PaymentRecordMapper payments;
    private ProductMapper catalog;
    private Station station;

    @BeforeEach void fixture() throws Exception {
        service = new TicketAccountServiceImpl();
        for (Field field : TicketAccountServiceImpl.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Autowired.class)) continue;
            Object dependency = dependencies.computeIfAbsent(field.getType(), type -> mock(type));
            field.setAccessible(true); field.set(service, dependency);
        }
        // Also supplies the before-fix fixture, where the station dependency does not yet exist.
        stations = dependency(StationMapper.class);
        payments = dependency(PaymentRecordMapper.class);
        catalog = dependency(ProductMapper.class);
        station = new Station(); station.setId(1L); station.setStatus(1);
        station.setOperatingStatus(StationOperatingStatus.NORMAL);
        when(stations.getById(1L)).thenReturn(station);
        Product product = new Product(); product.setId(5L); product.setCategory(2); product.setStatus(1);
        product.setTicketEnabled(1); product.setTicketPrice(new BigDecimal("8.00"));
        product.setPrice(new BigDecimal("20.00"));
        when(catalog.getById(5L)).thenReturn(product);
        Inventory inventory = new Inventory(); inventory.setEnabled(1);
        when(dependency(InventoryMapper.class).getByStationAndProduct(1L, 5L)).thenReturn(inventory);
        when(dependency(TicketTierService.class).usesCustomTicket(product, inventory)).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private <T> T dependency(Class<T> type) {
        return (T) dependencies.computeIfAbsent(type, clazz -> mock(clazz));
    }

    private PaymentRecord purchase(int method) {
        return service.purchaseTicket(7L, 5L, 3, method, 1L, "request", null, null);
    }

    private void noNewPaymentOrAssetWrites() {
        verify(payments, never()).insert(any());
        verifyNoInteractions(catalog, dependency(BarrelLedgerService.class),
                dependency(TicketLotService.class), dependency(TicketAccountMapper.class));
    }

    @ParameterizedTest @ValueSource(ints = {PayMethod.WECHAT, PayMethod.CASH})
    void hardClosedStationCannotCreateNewTicketMoney(int method) {
        station.setStatus(2);
        BusinessException error = assertThrows(BusinessException.class, () -> purchase(method));
        assertTrue(error.getMessage().contains("停业"));
        noNewPaymentOrAssetWrites();
    }

    @Test void nonexistentStationCannotCreateNewTicketMoney() {
        when(stations.getById(1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> purchase(PayMethod.CASH));
        noNewPaymentOrAssetWrites();
    }

    @Test void stationWithoutValidHardStateCannotCreateNewTicketMoney() {
        station.setStatus(null);
        assertThrows(BusinessException.class, () -> purchase(PayMethod.CASH));
        noNewPaymentOrAssetWrites();
    }

    static Stream<Integer> softStates() {
        return Stream.of(StationOperatingStatus.NORMAL, StationOperatingStatus.RESTING,
                StationOperatingStatus.APPOINTMENT_ONLY);
    }

    @ParameterizedTest @MethodSource("softStates")
    void softOperatingNoticeDoesNotCloseAnOpenStation(int softState) {
        station.setOperatingStatus(softState);
        PaymentRecord record = purchase(PayMethod.CASH);
        assertEquals(1L, record.getStationId());
        assertEquals(PaymentStatus.PENDING, record.getStatus());
        assertEquals(new BigDecimal("24.00"), record.getAmount());
        verify(payments).insert(record);
        verifyNoInteractions(dependency(TicketLotService.class), dependency(TicketAccountMapper.class));
    }

    private PaymentRecord original(int status) {
        PaymentRecord record = new PaymentRecord();
        record.setId(10L); record.setCustomerId(7L); record.setStationId(1L);
        record.setTicketWaterTypeId(5L); record.setTicketQty(3); record.setPaymentMethod(PayMethod.CASH);
        record.setStatus(status); record.setAmount(new BigDecimal("24.00"));
        record.setPurchaseRequestDigest(TicketPurchaseIntent.digest(7L, 1L, 5L, 3,
                PayMethod.CASH, null, null));
        return record;
    }

    @Test void paidPurchaseReplayAfterClosureReturnsItsOwnOriginalWithoutNewMoney() {
        station.setStatus(2);
        PaymentRecord original = original(PaymentStatus.PAID);
        when(payments.getByCustomerAndIdempotencyKeyForUpdate(7L, "request")).thenReturn(original);
        assertSame(original, purchase(PayMethod.CASH));
        verifyNoInteractions(stations);
        noNewPaymentOrAssetWrites();
    }

    @Test void replayCannotChangeOriginalContentAfterClosure() {
        station.setStatus(2);
        when(payments.getByCustomerAndIdempotencyKeyForUpdate(7L, "request"))
                .thenReturn(original(PaymentStatus.PAID));
        assertThrows(BusinessException.class, () -> service.purchaseTicket(7L, 5L, 4,
                PayMethod.CASH, 1L, "request", null, null));
        noNewPaymentOrAssetWrites();
    }

    @ParameterizedTest @ValueSource(ints = {PaymentStatus.PENDING, PaymentStatus.PAID,
            PaymentStatus.REFUNDED, PaymentStatus.CANCELLED})
    void ownHistoricalResultLookupIsReadOnlyEvenWhenStationIsClosed(int status) {
        station.setStatus(2);
        PaymentRecord original = original(status);
        when(payments.getByCustomerAndIdempotencyKey(7L, "request")).thenReturn(original);
        assertSame(original, service.findPurchaseResult(7L, "request"));
        assertNull(service.findPurchaseResult(8L, "request"));
        assertEquals(status, original.getStatus());
        verifyNoInteractions(stations);
        noNewPaymentOrAssetWrites();
    }

    @Test void existingTicketBalanceRemainsReadableWithoutFreezingAssets() {
        station.setStatus(2);
        TicketAccount account = new TicketAccount(); account.setRemainQuantity(4);
        when(dependency(TicketAccountMapper.class).getByCustomerProductStation(7L, 5L, 1L)).thenReturn(account);
        assertEquals(4, service.balanceOf(7L, 5L, 1L));
        verifyNoInteractions(stations, dependency(BarrelLedgerService.class), dependency(TicketLotService.class));
        verify(payments, never()).insert(any());
    }
}
