package com.example.aquaflow.service;

import com.example.aquaflow.controller.LoginController;
import com.example.aquaflow.dto.AuthRequestDTO;
import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.interceptor.AuthInterceptor;
import com.example.aquaflow.config.WebMvcConfig;
import com.example.aquaflow.interceptor.RateLimitInterceptor;
import com.example.aquaflow.interceptor.PublicRateLimitInterceptor;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.JwtUtil;
import com.example.aquaflow.util.PasswordUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Pure mock JDBC/mapper fixtures and real JWT/BCrypt. Never starts a server or resets a database. */
class TemporaryTestLoginTest {
    private static final String PASSWORD="fixture-only-not-a-deployment-password";
    private static final String HASH=PasswordUtil.encode(PASSWORD);
    private static final String URL="jdbc:mysql://127.0.0.1:3306/aquaflow_trial?serverTimezone=UTC";
    @AfterEach void clear(){
        AuthContext.clear();
        ((Map<?,?>)ReflectionTestUtils.getField(AuthTokenService.class,"LOGIN_ATTEMPTS")).clear();
    }

    @ParameterizedTest @CsvSource({"fixture-customer,customer,customer,11", "fixture-manager,staff,manager,12", "fixture-delivery,staff,delivery,13"})
    void fixedAccountsUseActualBusinessRoles(String name,String type,String role,long id)throws Exception{
        F f=new F();Map<String,Object> result=f.login(name);
        assertEquals(type,result.get("userType"));assertEquals(role,result.get("role"));
        assertEquals(id,((Number)result.get(type.equals("staff")?"staffId":"customerId")).longValue());
        var access=f.jwt.parseToken((String)result.get("accessToken"));
        assertEquals(TemporaryTestLogin.SOURCE,access.get("authSource"));assertEquals(name,access.get("testAccount"));
        assertEquals(id,((Number)access.get("userId")).longValue());
        assertEquals("customer".equals(type)?null:5L,access.get("stationId",Long.class));
        verify(f.staff,never()).insert(any());verify(f.customers,never()).insert(any());
    }

    @Test void usernameAndPasswordFailureDoesNotIssueOrCreateAnything()throws Exception{
        F f=new F();assertThrows(BusinessException.class,()->f.service.testLogin(params("missing",PASSWORD)));
        assertThrows(BusinessException.class,()->f.service.testLogin(params("fixture-customer","wrong")));
        assertThrows(BusinessException.class,()->f.service.testLogin(params("fixture-customer","中".repeat(30))));
        verifyNoInteractions(f.tokens);verify(f.customers,never()).insert(any());verify(f.staff,never()).insert(any());
    }

    @Test void defaultsAndExplicitProductionModeRejectWithoutDatabaseAccess()throws Exception{
        F f=new F();f.env.setProperty("app.test-login.enabled","false");
        assertThrows(BusinessException.class,()->f.login("fixture-customer"));verifyNoInteractions(f.datasource);
        f.env.setProperty("app.test-login.enabled","true");f.env.setProperty("app.payment.test-mode","false");
        assertThrows(BusinessException.class,()->f.login("fixture-manager"));verifyNoInteractions(f.datasource);
        f.env.setProperty("app.payment.test-mode","true");f.env.setProperty("spring.datasource.url",URL.replace("aquaflow_trial","aquaflow"));
        assertThrows(BusinessException.class,()->f.login("fixture-manager"));verifyNoInteractions(f.datasource);
    }

    @Test void actualCatalogAndJdbcTargetMustMatch()throws Exception{
        F f=new F();when(f.connection.getCatalog()).thenReturn("aquaflow");
        assertThrows(BusinessException.class,()->f.login("fixture-customer"));verifyNoInteractions(f.tokens);
    }

    @Test void cannotTakeOverWechatBoundCustomerOrStaff()throws Exception{
        F f=new F();f.customer.setOpenid("synthetic-bound-wechat");
        assertThrows(BusinessException.class,()->f.login("fixture-customer"));
        f.manager.setOpenid("synthetic-staff-wechat");assertThrows(BusinessException.class,()->f.login("fixture-manager"));
        verifyNoInteractions(f.tokens);
    }

    @Test void missingCustomerRelationshipInactiveStaffAndWrongStationReject()throws Exception{
        F f=new F();when(f.customers.countCustomerOfStation(11L,5L)).thenReturn(0);
        assertThrows(BusinessException.class,()->f.login("fixture-customer"));
        f.manager.setStatus(2);assertThrows(BusinessException.class,()->f.login("fixture-manager"));
        f.delivery.setStationId(6L);assertThrows(BusinessException.class,()->f.login("fixture-delivery"));
        f.manager.setStatus(1);f.manager.setRole(null);assertThrows(BusinessException.class,()->f.login("fixture-manager"));
        verifyNoInteractions(f.tokens);
    }

    @Test void disablingRevokesExistingAccessAndRefreshButNormalAccessStillWorks()throws Exception{
        F f=new F();var session=f.login("fixture-customer");
        String access=(String)session.get("accessToken"),refresh=(String)session.get("refreshToken");
        assertTrue(f.allowed(access));f.env.setProperty("app.test-login.enabled","false");
        assertFalse(f.allowed(access));assertThrows(BusinessException.class,()->f.refresh(refresh));
        assertTrue(f.allowed(f.jwt.generateAccessToken(11L,"customer","customer",null)));
        assertNull(f.jwt.parseToken(f.jwt.generateRefreshToken(11L,"customer")).get("authSource"));
    }

    @Test void refreshRetainsTestRestrictionAndRotatesNormally()throws Exception{
        F f=new F();var original=f.login("fixture-delivery");var rotated=f.refresh((String)original.get("refreshToken"));
        for(String field:List.of("accessToken","refreshToken")){
            var claims=f.jwt.parseToken((String)rotated.get(field));assertEquals(TemporaryTestLogin.SOURCE,claims.get("authSource"));
            if (field.equals("refreshToken")) {
                assertNull(claims.get("testAccount"));assertEquals(43,claims.get("testBinding",String.class).length());
            } else assertEquals("fixture-delivery",claims.get("testAccount"));
            assertEquals(13L,claims.get("userId",Long.class));
        }
        assertNotEquals(original.get("refreshToken"),rotated.get("refreshToken"));
        assertThrows(BusinessException.class,()->f.refresh((String)original.get("refreshToken")));
        f.env.setProperty("app.test-login.accounts-json","[]");assertFalse(f.allowed((String)rotated.get("accessToken")));
        assertThrows(BusinessException.class,()->f.refresh((String)rotated.get("refreshToken")));
    }

    @Test void mappingChangeOrNewWechatBindingRejectsExistingSessions()throws Exception{
        F f=new F();var session=f.login("fixture-manager");
        f.manager.setStationId(8L);assertFalse(f.allowed((String)session.get("accessToken")));
        assertThrows(BusinessException.class,()->f.refresh((String)session.get("refreshToken")));
        f.manager.setStationId(5L);f.manager.setOpenid("synthetic-binding-after-test-login");
        assertFalse(f.allowed((String)session.get("accessToken")));
        assertThrows(BusinessException.class,()->f.refresh((String)session.get("refreshToken")));
    }

    @Test void renamedLongAccountAndDisabledPaymentTestModeRevokeCompactRefresh()throws Exception{
        F f=new F();String name="c".repeat(48);
        f.env.setProperty("app.test-login.accounts-json",f.config(HASH).replace("fixture-customer",name));
        var session=f.login(name);var rotated=f.refresh((String)session.get("refreshToken"));
        assertTrue(f.allowed((String)rotated.get("accessToken")));
        f.env.setProperty("app.payment.test-mode","false");
        assertFalse(f.allowed((String)rotated.get("accessToken")));
        assertThrows(BusinessException.class,()->f.refresh((String)rotated.get("refreshToken")));
        f.env.setProperty("app.payment.test-mode","true");
        f.env.setProperty("app.test-login.accounts-json",f.config(HASH).replace("fixture-customer","d".repeat(48)));
        assertThrows(BusinessException.class,()->f.refresh((String)rotated.get("refreshToken")));
    }

    @Test void compactRefreshRejectsChangedBindingIdentityOrPartialMarkersWithoutConsumingToken()throws Exception{
        F f=new F();var session=f.login("fixture-delivery");String original=(String)session.get("refreshToken");
        List<Map<String,Object>> mutations=new java.util.ArrayList<>();
        for(int i=0;i<6;i++)mutations.add(new HashMap<>(f.jwt.parseToken(original)));
        mutations.get(0).put("userId",12L);mutations.get(1).put("userType","customer");
        mutations.get(2).put("testBinding","A".repeat(43));mutations.get(3).put("testAccount","fixture-delivery");
        mutations.get(4).remove("authSource");mutations.get(5).remove("testBinding");
        for(var claims:mutations){
            assertThrows(BusinessException.class,()->f.refresh(f.signed(claims)));
            assertTrue(f.stored.containsKey(original));
        }
        assertNotNull(f.refresh(original).get("refreshToken"));
    }

    @Test void legacyFullTestRefreshStillValidatesBindingAndRotatesToCompactFormat()throws Exception{
        F f=new F();var account=f.policy.login("fixture-manager",PASSWORD).account();
        var claims=new HashMap<String,Object>(f.jwt.parseToken(f.jwt.generateRefreshToken(12L,"staff")));
        claims.put("authSource",TemporaryTestLogin.SOURCE);claims.put("testAccount",account.name());
        claims.put("testBinding",f.policy.binding(account));String legacy=f.signed(claims);
        var row=new UserToken();row.setUserId(12L);row.setUserType("staff");row.setRefreshToken(legacy);f.stored.put(legacy,row);
        var rotated=f.refresh(legacy);var next=f.jwt.parseToken((String)rotated.get("refreshToken"));
        assertNull(next.get("testAccount"));assertEquals(43,next.get("testBinding",String.class).length());
        assertFalse(f.stored.containsKey(legacy));
        f.env.setProperty("app.test-login.enabled","false");
        assertThrows(BusinessException.class,()->f.refresh((String)rotated.get("refreshToken")));
    }

    @Test void passwordChangeChangesBindingWithoutStoringPlaintext()throws Exception{
        F f=new F();var session=f.login("fixture-customer");
        f.env.setProperty("app.test-login.accounts-json",f.config(PasswordUtil.encode("replacement-unit-fixture")));
        assertFalse(f.allowed((String)session.get("accessToken")));
        assertThrows(BusinessException.class,()->f.refresh((String)session.get("refreshToken")));
    }

    @Test void malformedDuplicateOrPlaintextConfigFailsClosed()throws Exception{
        F f=new F();for(String value:List.of("{bad", "[]",f.config("plaintext-not-a-hash"),f.config(HASH).replace("fixture-manager","fixture-customer"))){
            f.env.setProperty("app.test-login.accounts-json",value);assertThrows(BusinessException.class,()->f.login("fixture-customer"));
        }verifyNoInteractions(f.tokens);
    }

    @Test void httpBodyCannotChooseAnotherRoleOrBusinessId()throws Exception{
        F f=new F();LoginController controller=new LoginController();ReflectionTestUtils.setField(controller,"authTokenService",f.service);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        String json=new ObjectMapper().writeValueAsString(Map.of("account","fixture-delivery","password",PASSWORD,
                "role","STATION_MANAGER","userId",12,"stationId",999));
        var response=mvc.perform(post("/api/auth/test-login").contentType("application/json").content(json)).andReturn().getResponse();
        assertEquals(200,response.getStatus());var data=new ObjectMapper().readTree(response.getContentAsString()).path("data");
        assertEquals("delivery",data.path("role").asText());assertEquals(13,data.path("staffId").asLong());
        assertEquals(5,data.path("stationId").asLong());
    }

    @Test void newPublicLoginPathStillHasTheExistingIpLimiter() {
        var config=new WebMvcConfig();var auth=mock(AuthInterceptor.class);var rate=mock(RateLimitInterceptor.class);
        ReflectionTestUtils.setField(config,"authInterceptor",auth);
        ReflectionTestUtils.setField(config,"rateLimitInterceptor",rate);
        ReflectionTestUtils.setField(config,"publicRateLimitInterceptor",mock(PublicRateLimitInterceptor.class));
        var registry=new org.springframework.web.servlet.config.annotation.InterceptorRegistry();config.addInterceptors(registry);
        List<?> mapped=ReflectionTestUtils.invokeMethod(registry,"getInterceptors");
        var request=new MockHttpServletRequest("POST","/api/auth/test-login");
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(request);
        boolean limited=false,publicLogin=false;
        for(Object value:mapped)if(value instanceof org.springframework.web.servlet.handler.MappedInterceptor mapping){
            if(mapping.getInterceptor()==rate)limited=mapping.matches(request);
            if(mapping.getInterceptor()==auth)publicLogin=!mapping.matches(request);
        }
        assertTrue(limited);assertTrue(publicLogin);
    }

    private static AuthRequestDTO.TestLogin params(String account,String password){var p=new AuthRequestDTO.TestLogin();p.setAccount(account);p.setPassword(password);return p;}
    private static final class F{
        final MockEnvironment env=new MockEnvironment();final CustomerMapper customers=mock(CustomerMapper.class);
        final StaffMapper staff=mock(StaffMapper.class);final UserTokenMapper tokens=mock(UserTokenMapper.class);
        final DataSource datasource=mock(DataSource.class);final Connection connection=mock(Connection.class);
        final Customer customer=new Customer();final Staff manager=member(12,"STATION_MANAGER"),delivery=member(13,"DELIVERY");
        final JwtUtil jwt=new JwtUtil();final AuthTokenService service=new AuthTokenService();final AuthInterceptor interceptor=new AuthInterceptor();
        final Map<String,UserToken> stored=new HashMap<>();final TemporaryTestLogin policy;
        F()throws Exception{
            env.setProperty("app.test-login.enabled","true");env.setProperty("app.payment.test-mode","true");
            env.setProperty("spring.datasource.url",URL);env.setProperty("app.test-login.accounts-json",config(HASH));
            var metadata=mock(DatabaseMetaData.class);var statement=mock(Statement.class);var result=mock(ResultSet.class);
            when(datasource.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getURL()).thenReturn(URL);when(connection.getCatalog()).thenReturn("aquaflow_trial");
            when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery("SELECT DATABASE()")).thenReturn(result);
            when(result.next()).thenReturn(true);when(result.getString(1)).thenReturn("aquaflow_trial");
            customer.setId(11L);customer.setName("synthetic customer");when(customers.getById(11L)).thenReturn(customer);
            when(customers.countCustomerOfStation(11L,5L)).thenReturn(1);
            when(staff.getById(12L)).thenReturn(manager);when(staff.getById(13L)).thenReturn(delivery);
            ReflectionTestUtils.setField(jwt,"secret","temporary-login-unit-test-signing-material-not-a-deployment-secret-123456789");
            ReflectionTestUtils.setField(jwt,"accessTokenExpiry",7200000L);ReflectionTestUtils.setField(jwt,"refreshTokenExpiry",604800000L);
            doAnswer(i->{UserToken t=i.getArgument(0);assertTrue(t.getRefreshToken().length()<=500,"refresh token must fit the existing schema");stored.put(t.getRefreshToken(),t);return null;}).when(tokens).insert(any());
            when(tokens.findByRefreshToken(anyString())).thenAnswer(i->stored.get(i.getArgument(0)));
            doAnswer(i->{stored.remove(i.getArgument(0));return null;}).when(tokens).deleteByRefreshToken(anyString());
            policy=new TemporaryTestLogin(env,customers,staff,datasource);
            for(Object target:List.of(service,interceptor)){
                ReflectionTestUtils.setField(target,"jwtUtil",jwt);ReflectionTestUtils.setField(target,"temporaryTestLogin",policy);
                ReflectionTestUtils.setField(target,"staffMapper",staff);
            }
            ReflectionTestUtils.setField(service,"customerMapper",customers);ReflectionTestUtils.setField(service,"userTokenMapper",tokens);
            ReflectionTestUtils.setField(interceptor,"objectMapper",new ObjectMapper());
        }
        String config(String hash)throws Exception{return new ObjectMapper().writeValueAsString(List.of(
                Map.of("account","fixture-customer","passwordHash",hash,"userType","customer","userId",11,"stationId",5),
                Map.of("account","fixture-manager","passwordHash",hash,"userType","staff","userId",12,"stationId",5),
                Map.of("account","fixture-delivery","passwordHash",hash,"userType","staff","userId",13,"stationId",5)));}
        Map<String,Object> login(String name){return service.testLogin(params(name,PASSWORD));}
        Map<String,Object> refresh(String token){var p=new AuthRequestDTO.Refresh();p.setRefreshToken(token);return service.refresh(p);}
        String signed(Map<String,Object> claims){return io.jsonwebtoken.Jwts.builder().claims(claims)
                .signWith((javax.crypto.SecretKey)ReflectionTestUtils.invokeMethod(jwt,"getSigningKey")).compact();}
        boolean allowed(String token)throws Exception{
            var request=new MockHttpServletRequest("GET","/api/auth/me");request.addHeader("Authorization","Bearer "+token);
            var response=new MockHttpServletResponse();boolean result=interceptor.preHandle(request,response,new Object());
            if(!result)assertEquals(401,response.getStatus());AuthContext.clear();return result;
        }
        static Staff member(long id,String role){var s=new Staff();s.setId(id);s.setName("synthetic "+role);s.setRole(role);s.setStatus(1);s.setStationId(5L);return s;}
    }
}
