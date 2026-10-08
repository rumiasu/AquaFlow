package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.service.impl.StaffServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real employee service with mocked persistence: rejected commands must never reach a write. */
class StaffManagementGuardTest {
    private static final long STATION_ID = 1L;
    private static final long MANAGER_ID = 9L;
    private static final long STAFF_ID = 7L;
    private final StaffMapper mapper = mock(StaffMapper.class);
    private final StaffServiceImpl service = new StaffServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "staffMapper", mapper);
        AuthContext.set(new AuthContext.AuthUser(MANAGER_ID, "staff", "STATION_MANAGER", STATION_ID));
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"STATION_MANAGER", "manager", "ADMIN", "FACTORY_ADMIN", "unknown", "Delivery", " delivery"})
    void creationRejectsManagerAliasesAndUnknownRoles(String role) {
        assertThrows(BusinessException.class, () -> service.save(employee(STAFF_ID, role, STATION_ID, 1)));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY", "delivery"})
    void creationNormalizesDeliveryAndDefaultsToActive(String role) {
        AuthContext.set(new AuthContext.AuthUser(MANAGER_ID, "staff", "manager", STATION_ID));
        Staff employee = employee(STAFF_ID, role, 99L, null);

        service.save(employee);

        assertEquals("DELIVERY", employee.getRole());
        assertEquals(STATION_ID, employee.getStationId());
        assertEquals(1, employee.getStatus());
        assertNotNull(employee.getCreateTime());
        verify(mapper).insert(employee);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3, 99})
    void invalidStatusNeverWritesOnCreationOrUpdate(int status) {
        assertThrows(BusinessException.class, () -> service.save(employee(STAFF_ID, "DELIVERY", STATION_ID, status)));
        verifyNoInteractions(mapper);
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(employee(STAFF_ID, "DELIVERY", STATION_ID, 1));

        assertThrows(BusinessException.class, () -> service.update(patch(status)));

        verify(mapper).getByIdForUpdate(STAFF_ID);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATION_MANAGER", "manager"})
    void managerCannotDisableOrDeleteSelf(String role) {
        when(mapper.getByIdForUpdate(MANAGER_ID)).thenReturn(employee(MANAGER_ID, role, STATION_ID, 1));
        Staff patch = patch(2);
        patch.setId(MANAGER_ID);

        assertThrows(BusinessException.class, () -> service.update(patch));
        assertThrows(BusinessException.class, () -> service.delete(MANAGER_ID));

        verify(mapper, times(2)).getByIdForUpdate(MANAGER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATION_MANAGER", "manager"})
    void peerManagersCannotRemoveEachOtherOrTheSoleManager(String role) {
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(employee(STAFF_ID, role, STATION_ID, 1));
        assertUpdateAndDeleteRejected();
    }

    @Test
    void selfProtectionAlsoHoldsWhenTheStoredTargetRoleIsDelivery() {
        when(mapper.getByIdForUpdate(MANAGER_ID)).thenReturn(employee(MANAGER_ID, "DELIVERY", STATION_ID, 1));
        Staff patch = patch(2);
        patch.setId(MANAGER_ID);
        assertThrows(BusinessException.class, () -> service.update(patch));
        assertThrows(BusinessException.class, () -> service.delete(MANAGER_ID));
        verify(mapper, times(2)).getByIdForUpdate(MANAGER_ID);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void managerCanEditBasicsWithoutOverwritingRoleStationOrCredentials() {
        Staff existing = employee(STAFF_ID, "manager", STATION_ID, 1);
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(existing);
        when(mapper.updateBaseInfoIf(STAFF_ID, STATION_ID, "manager", 1, "新姓名", existing.getPhone(), 1)).thenReturn(1);
        Staff patch = patch(null);
        patch.setName("新姓名");
        patch.setRole("DELIVERY");
        patch.setStationId(99L);
        patch.setOpenid("forged-openid");
        patch.setPasswordHash("forged-hash");

        service.update(patch);

        verify(mapper).getByIdForUpdate(STAFF_ID);
        verify(mapper).updateBaseInfoIf(STAFF_ID, STATION_ID, "manager", 1, "新姓名", existing.getPhone(), 1);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY", "delivery"})
    void deliveryCanLeaveAndBeReactivated(String role) {
        Staff existing = employee(STAFF_ID, role, STATION_ID, 1);
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(existing);
        when(mapper.updateBaseInfoIf(STAFF_ID, STATION_ID, role, 1, existing.getName(), existing.getPhone(), 2)).thenReturn(1);
        when(mapper.updateBaseInfoIf(STAFF_ID, STATION_ID, role, 2, existing.getName(), existing.getPhone(), 1)).thenReturn(1);

        service.update(patch(2));
        existing.setStatus(2);
        service.update(patch(1));

        var calls = inOrder(mapper);
        calls.verify(mapper).getByIdForUpdate(STAFF_ID);
        calls.verify(mapper).updateBaseInfoIf(STAFF_ID, STATION_ID, role, 1, existing.getName(), existing.getPhone(), 2);
        calls.verify(mapper).getByIdForUpdate(STAFF_ID);
        calls.verify(mapper).updateBaseInfoIf(STAFF_ID, STATION_ID, role, 2, existing.getName(), existing.getPhone(), 1);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void deliveryCanBeDeletedInEitherEmploymentState(int status) {
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(employee(STAFF_ID, "DELIVERY", STATION_ID, status));
        when(mapper.deleteIf(STAFF_ID, STATION_ID, "DELIVERY", status)).thenReturn(1);
        service.delete(STAFF_ID);
        verify(mapper).getByIdForUpdate(STAFF_ID);
        verify(mapper).deleteIf(STAFF_ID, STATION_ID, "DELIVERY", status);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {2L})
    void currentReadRejectsUnboundOrTransferredTargets(Long stationId) {
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(employee(STAFF_ID, "DELIVERY", stationId, 1));
        assertUpdateAndDeleteRejected();
    }

    @Test
    void missingTargetNeverReturnsSuccessfulUpdateOrDelete() {
        assertUpdateAndDeleteRejected();
    }

    @Test
    void zeroAffectedRowsRejectsInsteadOfFallingBackToAnUnconditionalWrite() {
        Staff existing = employee(STAFF_ID, "DELIVERY", STATION_ID, 1);
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.update(patch(2)));
        assertThrows(BusinessException.class, () -> service.delete(STAFF_ID));

        verify(mapper, times(2)).getByIdForUpdate(STAFF_ID);
        verify(mapper).updateBaseInfoIf(STAFF_ID, STATION_ID, "DELIVERY", 1, existing.getName(), existing.getPhone(), 2);
        verify(mapper).deleteIf(STAFF_ID, STATION_ID, "DELIVERY", 1);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void unchangedPatchIsSuccessfulWithoutAnAmbiguousZeroRowUpdate() {
        when(mapper.getByIdForUpdate(STAFF_ID)).thenReturn(employee(STAFF_ID, "DELIVERY", STATION_ID, 1));
        service.update(patch(null));
        verify(mapper).getByIdForUpdate(STAFF_ID);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY", "delivery", "UNSELECTED", "ADMIN"})
    void nonManagerCannotBypassControllerThroughTheService(String role) {
        AuthContext.set(new AuthContext.AuthUser(MANAGER_ID, "staff", role, STATION_ID));
        assertAllWritesRejectedWithoutQuery();
    }

    @Test
    void customerWithManagerRoleStillCannotWrite() {
        AuthContext.set(new AuthContext.AuthUser(MANAGER_ID, "customer", "manager", STATION_ID));
        assertAllWritesRejectedWithoutQuery();
    }

    @Test
    void unboundManagerAndMissingSessionCannotWrite() {
        AuthContext.set(new AuthContext.AuthUser(MANAGER_ID, "staff", "manager", null));
        assertAllWritesRejectedWithoutQuery();
        AuthContext.clear();
        assertAllWritesRejectedWithoutQuery();
    }

    private void assertUpdateAndDeleteRejected() {
        assertThrows(BusinessException.class, () -> service.update(patch(2)));
        assertThrows(BusinessException.class, () -> service.delete(STAFF_ID));
        verify(mapper, times(2)).getByIdForUpdate(STAFF_ID);
        verifyNoMoreInteractions(mapper);
    }

    private void assertAllWritesRejectedWithoutQuery() {
        assertThrows(BusinessException.class, () -> service.save(employee(STAFF_ID, "DELIVERY", STATION_ID, 1)));
        assertThrows(BusinessException.class, () -> service.update(patch(2)));
        assertThrows(BusinessException.class, () -> service.delete(STAFF_ID));
        verifyNoInteractions(mapper);
    }

    private Staff patch(Integer status) {
        Staff patch = new Staff();
        patch.setId(STAFF_ID);
        patch.setStatus(status);
        return patch;
    }

    private Staff employee(long id, String role, Long stationId, Integer status) {
        Staff employee = new Staff();
        employee.setId(id);
        employee.setName("原姓名");
        employee.setPhone("13800000001");
        employee.setRole(role);
        employee.setStationId(stationId);
        employee.setStatus(status);
        return employee;
    }
}
