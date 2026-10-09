package com.example.aquaflow.service;

import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.dto.AgreementLoginDTO;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transaction advice with a fake manager: no Spring application, HTTP, JDBC or WeChat call. */
class StaffWechatLoginBoundaryTest {
    private static final String CODE = "staff-login-boundary-fixture";
    private static final String OPENID = "staff-login-boundary-openid-fixture";
    private WeChatLoginService provider;
    private AgreementAcknowledgementService evidence;
    private StaffMapper staffMapper;
    private CustomerMapper customerMapper;
    private UserTokenMapper tokenMapper;
    private PlatformTransactionManager transactions;
    private TransactionStatus status;
    private AuthTokenService service;

    @BeforeEach
    void setup() {
        AuthTokenService target = new AuthTokenService();
        provider = spy(new WeChatLoginService());
        evidence = mock(AgreementAcknowledgementService.class);
        staffMapper = mock(StaffMapper.class);
        customerMapper = mock(CustomerMapper.class);
        tokenMapper = mock(UserTokenMapper.class);
        AgreementCatalogService catalog = mock(AgreementCatalogService.class);
        when(catalog.catalog("STAFF")).thenReturn(new AgreementCatalogService.Catalog(false, "draft", List.of()));
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.generateRefreshToken(anyLong(), eq("staff"))).thenReturn("fixture-refresh");
        ReflectionTestUtils.setField(target, "weChatLoginService", provider);
        ReflectionTestUtils.setField(target, "agreementAcknowledgementService", evidence);
        ReflectionTestUtils.setField(target, "agreementCatalogService", catalog);
        ReflectionTestUtils.setField(target, "staffMapper", staffMapper);
        ReflectionTestUtils.setField(target, "customerMapper", customerMapper);
        ReflectionTestUtils.setField(target, "userTokenMapper", tokenMapper);
        ReflectionTestUtils.setField(target, "jwtUtil", jwt);
        transactions = mock(PlatformTransactionManager.class);
        status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        service = (AuthTokenService) proxy.getProxy();
    }

    private AuthRequestDTO.WxLoginStaff request() {
        AuthRequestDTO.WxLoginStaff request = new AuthRequestDTO.WxLoginStaff();
        request.setCode(CODE);
        return request;
    }

    private void assertRollback() {
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void providerBusinessErrorKeepsOriginalExceptionAndStopsBeforeDatabaseWrites() {
        BusinessException failure = new BusinessException("微信登录失败，请重新点击登录");
        doThrow(failure).when(provider).code2Session(WeChatApp.STAFF, CODE);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.wxLoginStaff(request())));
        verify(provider).code2Session(WeChatApp.STAFF, CODE);
        verifyNoInteractions(staffMapper, customerMapper, tokenMapper);
        verify(evidence, never()).recordLogin(anyString(), anyLong(), any());
        assertRollback();
    }

    @Test
    void providerRuntimeErrorKeepsStaffBusinessClassificationAndStopsBeforeWrites() {
        doThrow(new NumberFormatException("synthetic response code")).when(provider).code2Session(WeChatApp.STAFF, CODE);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.wxLoginStaff(request()));
        assertEquals("微信登录失败: synthetic response code", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(staffMapper, customerMapper, tokenMapper);
        verify(evidence, never()).recordLogin(anyString(), anyLong(), any());
        assertRollback();
    }

    @Test
    void invalidAgreementFailsBeforeExchangingWechatCodeOrWritingAnything() {
        AuthRequestDTO.WxLoginStaff request = request();
        AgreementLoginDTO versions = new AgreementLoginDTO();
        request.setAgreement(versions);
        BusinessException failure = new BusinessException("协议版本已变化");
        doThrow(failure).when(evidence).validateLogin("STAFF", versions);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.wxLoginStaff(request)));
        verifyNoInteractions(provider, staffMapper, customerMapper, tokenMapper);
        verify(evidence, never()).recordLogin(anyString(), anyLong(), any());
        assertRollback();
    }

    @Test
    void disabledStaffCannotRecordAgreementOrReceiveTokens() {
        doReturn(Map.of("openid", OPENID)).when(provider).code2Session(WeChatApp.STAFF, CODE);
        Staff staff = new Staff();
        staff.setId(23L);
        staff.setStatus(2);
        when(staffMapper.findByOpenid(OPENID)).thenReturn(staff);
        assertEquals("该账号已停用", assertThrows(BusinessException.class, () -> service.wxLoginStaff(request())).getMessage());
        verifyNoInteractions(customerMapper, tokenMapper);
        verify(evidence, never()).recordLogin(anyString(), anyLong(), any());
        assertRollback();
    }

    @Test
    void unselectedIdentityNeverBecomesAgreementEvidence() {
        doReturn(Map.of("openid", OPENID)).when(provider).code2Session(WeChatApp.STAFF, CODE);
        Map<String, Object> result = service.wxLoginStaff(request());
        assertEquals("UNSELECTED", result.get("role"));
        assertEquals(true, result.get("needSelectRole"));
        assertEquals(false, result.get("agreementRecorded"));
        assertNull(result.get("staffId"));
        assertNull(result.get("stationId"));
        verify(evidence, never()).recordLogin(anyString(), anyLong(), any());
        verifyNoInteractions(customerMapper);
        verify(transactions).commit(status);
        verify(transactions, never()).rollback(any());
    }

    @Test
    void tokenPersistenceFailureAfterAgreementStillRollsBackLoginTransaction() {
        doReturn(Map.of("openid", OPENID)).when(provider).code2Session(WeChatApp.STAFF, CODE);
        Staff staff = new Staff();
        staff.setId(23L);
        staff.setStatus(1);
        staff.setRole("DELIVERY");
        staff.setStationId(7L);
        when(staffMapper.findByOpenid(OPENID)).thenReturn(staff);
        when(evidence.recordLogin(eq("staff"), eq(23L), any())).thenReturn(true);
        doThrow(new IllegalStateException("synthetic token store fault")).when(tokenMapper).insert(any(UserToken.class));
        AuthRequestDTO.WxLoginStaff request = request();
        AgreementLoginDTO versions = new AgreementLoginDTO();
        versions.setTermsVersionId("staff-user-" + "a".repeat(64));
        versions.setPrivacyVersionId("staff-privacy-" + "b".repeat(64));
        request.setAgreement(versions);
        assertThrows(BusinessException.class, () -> service.wxLoginStaff(request));
        verify(evidence).recordLogin("staff", 23L, versions);
        verify(tokenMapper).deleteByUser(23L, "staff");
        verifyNoInteractions(customerMapper);
        assertRollback();
    }
}
