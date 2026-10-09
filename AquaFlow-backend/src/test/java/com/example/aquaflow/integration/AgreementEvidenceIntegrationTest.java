package com.example.aquaflow.integration;

import com.example.aquaflow.service.AgreementCatalogService;
import com.example.aquaflow.service.AgreementAcknowledgementService;
import com.example.aquaflow.service.AgreementTestCatalog;
import com.example.aquaflow.dto.AgreementLoginDTO;
import com.example.aquaflow.entity.UserToken;
import com.example.aquaflow.mapper.UserTokenMapper;
import com.example.aquaflow.service.WeChatLoginService;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses test-only synthetic reviewed text, never the repository's real placeholder drafts. */
@Import(AgreementEvidenceIntegrationTest.SyntheticCatalog.class)
class AgreementEvidenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired AgreementAcknowledgementService evidence;
    // 2026-10-08：Boot 4 不再提供旧注解；用当前 Spring 的覆盖/包装注解保留真实 HTTP 回滚反例。
    @MockitoBean WeChatLoginService provider;
    @MockitoSpyBean UserTokenMapper tokenMapper;
    Api post(String path,String token,Map<String,?> body) {
        try{return super.post(path,token,om.writeValueAsString(body));}catch(Exception e){throw new IllegalStateException(e);}
    }
    @TestConfiguration static class SyntheticCatalog {
        @Bean @Primary AgreementCatalogService syntheticAgreementCatalog() {return AgreementTestCatalog.published();}
    }
    String version(String audience,String type) {
        for(var d:get("/api/agreements/current?audience="+audience,null).data().path("documents"))if(type.equals(d.path("type").asText()))return d.path("versionId").asText();
        throw new AssertionError("missing synthetic document");
    }
    @Test void actorScopeEventMeaningAndTimestampAreServerOwnedAndRetryIsAppendOnly() {
        long customer=createCustomer("合成本人","agreement-own"),other=createCustomer("合成他人","agreement-other");String token=customerToken(customer),v=version("CUSTOMER","privacy");
        var body=Map.of("type","privacy","versionId",v);assertEquals(0,post("/api/agreements/acknowledgements",token,body).code());
        var evidence=jdbc.queryForList("select * from agreement_acknowledgement");assertEquals(0,post("/api/agreements/acknowledgements",token,body).code());assertEquals(evidence,jdbc.queryForList("select * from agreement_acknowledgement"));
        assertEquals("PRIVACY_NOTICE_ACKNOWLEDGEMENT",evidence.get(0).get("event_type"));assertEquals("EXPLICIT_ACTION",evidence.get(0).get("action_source"));
        assertEquals(1,get("/api/agreements/acknowledgements/my",token).data().size());assertEquals(0,get("/api/agreements/acknowledgements/my",customerToken(other)).data().size());
        long station=createStation("合成员工站"),staff=createStaff("合成员工","DELIVERY",station,1);
        assertEquals(1,post("/api/agreements/acknowledgements",staffToken(staff,"DELIVERY",station),body).code());
        assertEquals(0,post("/api/agreements/acknowledgements",staffToken(staff,"DELIVERY",station),Map.of("type","user","versionId",version("STAFF","user"))).code());
        assertEquals(1,intOf("select count(*) from agreement_acknowledgement where actor_type='customer' and actor_id=?",customer));
        assertEquals(1,intOf("select count(*) from agreement_acknowledgement where actor_type='staff' and actor_id=?",staff));
    }
    @Test void oldVersionsRemainReadableButCannotBeAcceptedAndConflictingSnapshotCannotBeOverwritten() {
        long customer=createCustomer("合成本人","agreement-conflict");String token=customerToken(customer),v=version("CUSTOMER","user");
        String older=AgreementTestCatalog.archivedVersion(AgreementTestCatalog.fixture(true,true,Instant.EPOCH),"CUSTOMER","user");
        assertEquals(0,get("/api/agreements/documents/"+older,null).code());assertEquals(1,post("/api/agreements/acknowledgements",token,Map.of("type","user","versionId",older)).code());
        jdbc.update("insert into agreement_document(version_id,audience,document_type,content_sha256,body_json,create_time) values(?,'CUSTOMER','user',?,'{}',now())",v,v.substring(v.length()-64));
        var before=jdbc.queryForList("select * from agreement_document");assertEquals(1,post("/api/agreements/acknowledgements",token,Map.of("type","user","versionId",v)).code());
        assertEquals(before,jdbc.queryForList("select * from agreement_document"));assertEquals(0,intOf("select count(*) from agreement_acknowledgement"));
    }
    @Test void concurrentRepeatedActionsKeepOneOriginalEvidenceRow() {
        long customer=createCustomer("并发本人","agreement-race");String token=customerToken(customer);var body=Map.of("type","user","versionId",version("CUSTOMER","user"));
        var first=CompletableFuture.supplyAsync(()->post("/api/agreements/acknowledgements",token,body));
        var second=CompletableFuture.supplyAsync(()->post("/api/agreements/acknowledgements",token,body));
        assertEquals(0,first.join().code());assertEquals(0,second.join().code());
        assertEquals(1,intOf("select count(*) from agreement_document"));assertEquals(1,intOf("select count(*) from agreement_acknowledgement"));
    }
    @Test void loginEvidenceWritesBothEventsAtomicallyAndSecondSnapshotConflictRollsBackFirst() {
        long customer=createCustomer("事务本人","agreement-transaction");var dto=new AgreementLoginDTO();dto.setTermsVersionId(version("CUSTOMER","user"));dto.setPrivacyVersionId(version("CUSTOMER","privacy"));
        jdbc.update("insert into agreement_document(version_id,audience,document_type,content_sha256,body_json,create_time) values(?,'CUSTOMER','privacy',?,'{}',now())",dto.getPrivacyVersionId(),dto.getPrivacyVersionId().substring(dto.getPrivacyVersionId().length()-64));
        var before=jdbc.queryForList("select * from agreement_document");assertThrows(BusinessException.class,()->evidence.recordLogin("customer",customer,dto));
        assertEquals(before,jdbc.queryForList("select * from agreement_document"));assertEquals(0,intOf("select count(*) from agreement_acknowledgement"));
        long staffStation=createStation("合成员工站"),staff=createStaff("事务员工","DELIVERY",staffStation,1);var employee=new AgreementLoginDTO();employee.setTermsVersionId(version("STAFF","user"));employee.setPrivacyVersionId(version("STAFF","privacy"));
        assertTrue(evidence.recordLogin("staff",staff,employee));assertEquals(2,intOf("select count(*) from agreement_acknowledgement where actor_type='staff' and actor_id=? and action_source='LOGIN_CLICK'",staff));
    }

    @Test void failedStaffLoginRollsBackAgreementRowsAndPreservesPreviousRefreshToken() {
        long station=createStation("合成登录回滚站"),staff=createStaff("合成登录员工","DELIVERY",station,1);
        String openid="agreement-staff-login-failure-fixture",code="agreement-staff-login-failure-code";
        jdbc.update("update staff set openid=? where id=?",openid,staff);
        jdbc.update("insert into user_token(user_id,user_type,refresh_token,expire_time,create_time) values(?,'staff','agreement-original-token-fixture',date_add(now(),interval 1 day),now())",staff);
        var previousTokens=jdbc.queryForList("select * from user_token");
        var previousDocuments=jdbc.queryForList("select * from agreement_document");
        var previousEvents=jdbc.queryForList("select * from agreement_acknowledgement");
        int alerts=intOf("select count(*) from alert_log where alert_type='SYSTEM'");
        when(provider.staffCode2Session(code)).thenReturn(Map.of("openid",openid));
        doThrow(new IllegalStateException("synthetic token store fault")).when(tokenMapper).insert(any(UserToken.class));
        var agreement=Map.of("termsVersionId",version("STAFF","user"),"privacyVersionId",version("STAFF","privacy"));
        Api failed=post("/api/auth/wx-login-staff",null,Map.of("code",code,"agreement",agreement));
        assertEquals(1,failed.code(),failed.toString());
        assertEquals(previousDocuments,jdbc.queryForList("select * from agreement_document"));
        assertEquals(previousEvents,jdbc.queryForList("select * from agreement_acknowledgement"));
        assertEquals(previousTokens,jdbc.queryForList("select * from user_token"));
        assertEquals(alerts,intOf("select count(*) from alert_log where alert_type='SYSTEM'"));
        verify(tokenMapper).deleteByUser(staff,"staff");
    }
}
