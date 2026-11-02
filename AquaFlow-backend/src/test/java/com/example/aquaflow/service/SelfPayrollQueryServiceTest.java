package com.example.aquaflow.service;

import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelfPayrollQueryServiceTest {
    StaffPayrollMapper p;StaffEarningMapper e;SelfPayrollQueryService service;
    @BeforeEach void setup(){p=mock(StaffPayrollMapper.class);e=mock(StaffEarningMapper.class);service=new SelfPayrollQueryService(p,e);AuthContext.set(new AuthContext.AuthUser(7L,"staff","DELIVERY",2L));}
    @AfterEach void clear(){AuthContext.clear();}
    @Test void cursorPageKeepsOwnIdentityAndReturnsStrictBoundary(){var rows=new ArrayList<StaffPayroll>();for(long id=100;id>=80;id--){var row=new StaffPayroll();row.setId(id);row.setStaffId(7L);rows.add(row);}when(p.listByStaffBefore(7L,120L,21)).thenReturn(rows);var out=service.page(120L);assertEquals(true,out.get("hasMore"));assertEquals(81L,out.get("nextBeforeId"));assertEquals(20,((List<?>)out.get("payrolls")).size());verify(p).listByStaffBefore(7L,120L,21);}
    @Test void terminalEmptyPageHasNoNextCursor(){when(p.listByStaffBefore(7L,81L,21)).thenReturn(List.of());var out=service.page(81L);assertEquals(false,out.get("hasMore"));assertNull(out.get("nextBeforeId"));}
    @Test void invalidCursorOrCustomerCannotRead(){assertThrows(BusinessException.class,()->service.page(0L));AuthContext.set(new AuthContext.AuthUser(7L,"customer","CUSTOMER",null));assertThrows(BusinessException.class,()->service.page(null));verifyNoInteractions(p,e);}
    @Test void anotherEmployeesPayrollNeverReadsItsEarnings(){var row=new StaffPayroll();row.setStaffId(8L);when(p.getById(41L)).thenReturn(row);assertThrows(BusinessException.class,()->service.detail(41L));verifyNoInteractions(e);}
    @Test void ownDetailAlsoScopesEarningRowsByActor(){var row=new StaffPayroll();row.setId(41L);row.setStaffId(7L);when(p.getById(41L)).thenReturn(row);when(e.listByPayrollForStaff(41L,7L)).thenReturn(List.of());assertEquals(row,service.detail(41L).get("payroll"));verify(e).listByPayrollForStaff(41L,7L);}
}
