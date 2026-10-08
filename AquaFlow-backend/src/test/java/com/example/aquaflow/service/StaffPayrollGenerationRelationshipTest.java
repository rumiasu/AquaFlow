package com.example.aquaflow.service;

import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.StaffEarningMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.service.impl.StaffEarningServiceImpl;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the real payroll service with mocked persistence; never connects to a database. */
class StaffPayrollGenerationRelationshipTest {
    private static final long STATION_ID = 1L;
    private static final long STAFF_ID = 7L;
    private static final long PAYROLL_ID = 44L;
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private final StaffEarningMapper earnings = mock(StaffEarningMapper.class);
    private final StaffPayrollMapper payrolls = mock(StaffPayrollMapper.class);
    private final StaffMapper staff = mock(StaffMapper.class);
    private final BusinessTime time = mock(BusinessTime.class);
    private final StaffEarningServiceImpl service = new StaffEarningServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "staffEarningMapper", earnings);
        ReflectionTestUtils.setField(service, "staffPayrollMapper", payrolls);
        ReflectionTestUtils.setField(service, "staffMapper", staff);
        ReflectionTestUtils.setField(service, "businessTime", time);
        AuthContext.set(new AuthContext.AuthUser(9L, "staff", "STATION_MANAGER", STATION_ID));
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 7));
        doAnswer(invocation -> {
            StaffPayroll payroll = invocation.getArgument(0);
            payroll.setId(PAYROLL_ID);
            return 1;
        }).when(payrolls).insert(any(StaffPayroll.class));
        when(earnings.sumByPayroll(PAYROLL_ID)).thenReturn(new BigDecimal("14.00"));
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void unrelatedOtherStationEmployeeIsRejectedBeforePayrollReadsOrWrites() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(2L, "DELIVERY", 1));
        assertUnrelatedRejected();
    }

    @Test
    void nonexistentEmployeeWithoutLocalEarningsIsRejectedBeforePayrollReadsOrWrites() {
        assertUnrelatedRejected();
    }

    @Test
    void unsupportedCurrentRoleWithoutLocalEarningsIsRejected() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(STATION_ID, "UNSELECTED", 1));
        assertUnrelatedRejected();
    }

    @Test
    void currentDeliveryEmployeeMayGenerateAnEmptyPayroll() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(STATION_ID, "DELIVERY", 1));
        when(earnings.sumByPayroll(PAYROLL_ID)).thenReturn(null);
        assertEquals(PAYROLL_ID, generate());
        verify(earnings).countByStationAndStaff(STATION_ID, STAFF_ID);
        verify(payrolls).setTotalAmount(PAYROLL_ID, BigDecimal.ZERO);
    }

    @Test
    void currentStationManagerRemainsEligible() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(STATION_ID, "STATION_MANAGER", 1));
        assertScopedPayrollGenerated();
    }

    @Test
    void transferredEmployeeWithLocalEarningsMaySettleHistoricalWork() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(2L, "DELIVERY", 1));
        assertHistoricalPayrollGenerated();
    }

    @Test
    void unboundEmployeeWithLocalEarningsMaySettleHistoricalWork() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(null, "DELIVERY", 1));
        assertHistoricalPayrollGenerated();
    }

    @Test
    void departedEmployeeWithLocalEarningsMaySettleHistoricalWork() {
        when(staff.getById(STAFF_ID)).thenReturn(employee(null, "DELIVERY", 2));
        assertHistoricalPayrollGenerated();
    }

    @Test
    void physicallyDeletedEmployeeWithLocalEarningsMaySettleHistoricalWork() {
        assertHistoricalPayrollGenerated();
    }

    @Test
    void duplicatePeriodStillRejectsBeforeAnyPayrollWrite() {
        when(earnings.countByStationAndStaff(STATION_ID, STAFF_ID)).thenReturn(1);
        StaffPayroll existing = new StaffPayroll();
        existing.setPayrollNo("PR-EXISTING");
        when(payrolls.getByPeriod(STATION_ID, STAFF_ID, START, END)).thenReturn(existing);
        BusinessException error = assertThrows(BusinessException.class, this::generate);
        assertTrue(error.getMessage().contains("已有结算单"));
        verify(payrolls, never()).insert(any());
        verify(earnings, never()).attachToPayroll(any(), any(), any(), any(), any());
    }

    @Test
    void payrollHistoryDoesNotGrantManualAdjustmentToADeletedEmployee() {
        when(earnings.countByStationAndStaff(STATION_ID, STAFF_ID)).thenReturn(1);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.adjustEarning(STATION_ID, STAFF_ID, null, BigDecimal.ONE, "synthetic"));
        assertEquals("配送员不存在", error.getMessage());
        verifyNoInteractions(earnings, payrolls);
    }

    private void assertUnrelatedRejected() {
        BusinessException error = assertThrows(BusinessException.class, this::generate);
        assertEquals("该员工与本站没有工资结算关系", error.getMessage());
        verify(earnings).countByStationAndStaff(STATION_ID, STAFF_ID);
        verifyNoInteractions(payrolls);
        verify(earnings, never()).attachToPayroll(any(), any(), any(), any(), any());
        verify(earnings, never()).insert(any());
    }

    private void assertHistoricalPayrollGenerated() {
        when(earnings.countByStationAndStaff(STATION_ID, STAFF_ID)).thenReturn(1);
        assertScopedPayrollGenerated();
        verifyNoInteractions(staff);
    }

    private void assertScopedPayrollGenerated() {
        assertEquals(PAYROLL_ID, generate());
        ArgumentCaptor<StaffPayroll> inserted = ArgumentCaptor.forClass(StaffPayroll.class);
        verify(payrolls).insert(inserted.capture());
        assertEquals(STATION_ID, inserted.getValue().getStationId());
        assertEquals(STAFF_ID, inserted.getValue().getStaffId());
        assertEquals(StaffPayroll.Status.DRAFT, inserted.getValue().getStatus());
        verify(earnings).attachToPayroll(PAYROLL_ID, STATION_ID, STAFF_ID,
                START.atStartOfDay(), END.plusDays(1).atStartOfDay());
        verify(payrolls).setTotalAmount(PAYROLL_ID, new BigDecimal("14.00"));
    }

    private Long generate() {
        return service.generatePayroll(STATION_ID, STAFF_ID, START, END, "synthetic");
    }

    private Staff employee(Long stationId, String role, int status) {
        Staff employee = new Staff();
        employee.setId(STAFF_ID);
        employee.setStationId(stationId);
        employee.setRole(role);
        employee.setStatus(status);
        return employee;
    }
}
