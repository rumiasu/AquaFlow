package com.example.aquaflow.service;

import com.example.aquaflow.dto.AccountDataRequestDTO;
import com.example.aquaflow.entity.AccountDataRequest;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AccountDataRequestMapper;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDataRequestServiceTest {
    AccountDataRequestMapper mapper=mock(AccountDataRequestMapper.class);
    AccountClosureCheckService closure=mock(AccountClosureCheckService.class);
    @BeforeEach void actor() {AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null));when(mapper.customerExists(7L)).thenReturn(1);}
    @AfterEach void clear() {AuthContext.clear();}
    AccountDataRequestService service(boolean enabled) {
        return new AccountDataRequestService(mapper,closure,AgreementTestCatalog.CLOCK,enabled,"仅测试受理人","仅测试联系渠道");
    }
    AccountDataRequestDTO request(String type,String key,String note) {
        var dto=new AccountDataRequestDTO();dto.setRequestType(type);dto.setIdempotencyKey(key);dto.setNote(note);return dto;
    }
    @Test void disabledIntakeShowsNoInventedContactAndWritesNothing() {
        var service=service(false);assertFalse(service.options().intakeEnabled());assertNull(service.options().intakeContact());
        assertThrows(BusinessException.class,()->service.submit(request("CLOSURE","test-disabled","")));
        verify(mapper,never()).insert(any());verifyNoInteractions(closure);
        assertThrows(IllegalStateException.class,()->new AccountDataRequestService(mapper,closure,AgreementTestCatalog.CLOCK,true,"待补","待拍板"));
    }
    @Test void unsettledAccountDoesNotPreventFilingAndRetryUsesOriginalCurrentRead() {
        var stored=new AccountDataRequest[1];
        doAnswer(call->{var row=(AccountDataRequest)call.getArgument(0);row.setId(9L);stored[0]=row;return 1;}).when(mapper).insert(any());
        when(mapper.findReplayForUpdate("customer",7L,"test-request")).thenAnswer(call->stored[0]);
        var dto=request("CLOSURE","test-request","希望人工核实");var first=service(true).submit(dto);
        assertEquals("SUBMITTED",first.status());assertEquals(AgreementTestCatalog.CLOCK.instant().atZone(AgreementTestCatalog.CLOCK.getZone()).toLocalDateTime(),first.submittedAt());
        verifyNoInteractions(closure); // Eligibility is a separate read, not a filing gate.
        when(mapper.findReplay("customer",7L,"test-request")).thenAnswer(call->stored[0]);
        assertEquals(first,service(false).submit(dto));verify(mapper,times(1)).insert(any());
        assertThrows(BusinessException.class,()->service(true).submit(request("EXPORT","test-request","希望人工核实")));
        assertThrows(BusinessException.class,()->service(true).submit(request("CLOSURE","test-request","更改内容")));
    }
    @Test void duplicateWaitUsesCurrentReadAndRejectsDifferentContent() {
        var dto=request("ACCESS","race-key","");
        doAnswer(call->{var row=(AccountDataRequest)call.getArgument(0);row.setId(11L);row.setRequestDigest("different");
            when(mapper.findReplayForUpdate("customer",7L,"race-key")).thenReturn(row);return 0;}).when(mapper).insert(any());
        assertThrows(BusinessException.class,()->service(true).submit(dto));verify(mapper).findReplayForUpdate("customer",7L,"race-key");
    }
    @Test void anonymousVirtualAndMissingIdentityAreRejected() {
        AuthContext.clear();assertThrows(BusinessException.class,()->service(true).options());
        AuthContext.set(new AuthContext.AuthUser(-7L,"staff","UNSELECTED",null));assertThrows(BusinessException.class,()->service(true).options());
        AuthContext.set(new AuthContext.AuthUser(88L,"customer",null,null));assertThrows(BusinessException.class,()->service(true).mine(null));
        verify(mapper,never()).insert(any());verify(mapper,never()).listMine(anyString(),anyLong(),any());
    }
    @Test void employeeClosureDoesNotPretendCustomerCheckAndDetailIsScoped() {
        AuthContext.set(new AuthContext.AuthUser(7L,"staff","DELIVERY",2L));when(mapper.staffExists(7L)).thenReturn(1);
        var row=new AccountDataRequest();row.setId(3L);row.setRequestType("CLOSURE");row.setStatus("SUBMITTED");row.setNote("");
        when(mapper.mine(3L,"staff",7L)).thenReturn(row);var result=service(false).detail(3L);
        assertNull(result.closureCheck());assertTrue(result.checkNotice().contains("未完成员工清结检查"));assertFalse(service(false).options().closureCheckSupported());
        assertThrows(BusinessException.class,()->service(false).detail(4L));verifyNoInteractions(closure);
    }
    @Test void paginationDoesNotLoseOlderRequestsAndUnknownStatusIsConservative() {
        List<AccountDataRequest> rows=new ArrayList<>();for(long id=100;id>=50;id--){var row=new AccountDataRequest();row.setId(id);row.setRequestType("ACCESS");row.setStatus("FUTURE");rows.add(row);}
        when(mapper.listMine("customer",7L,null)).thenReturn(rows);var result=service(false).mine(null);
        assertEquals(50,result.items().size());assertEquals(51L,result.nextBeforeId());assertEquals("状态待人工核实",result.items().get(0).statusText());
        assertThrows(BusinessException.class,()->service(false).mine(0L));
    }
}
