package com.example.aquaflow.service;

import com.example.aquaflow.dto.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.AgreementEvidenceMapper;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgreementAcknowledgementServiceTest {
    AgreementEvidenceMapper mapper;AgreementCatalogService catalog;AgreementAcknowledgementService service;
    Map<String,Map<String,Object>> bodies;
    @BeforeEach void setup() {
        catalog=AgreementTestCatalog.published();mapper=mock(AgreementEvidenceMapper.class);bodies=new HashMap<>();
        service=new AgreementAcknowledgementService(catalog,mapper,AgreementTestCatalog.CLOCK);
        doAnswer(call->{bodies.putIfAbsent(call.getArgument(0),Map.of("audience",call.getArgument(1),"documentType",call.getArgument(2),"contentSha256",call.getArgument(3),"bodyJson",call.getArgument(4)));return 1;})
                .when(mapper).insertDocument(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        when(mapper.documentForUpdate(anyString())).thenAnswer(call->bodies.get(call.getArgument(0)));
        AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null));
    }
    @AfterEach void clear(){AuthContext.clear();}
    AgreementLoginDTO login(String audience) {
        var docs=catalog.catalog(audience).documents();var dto=new AgreementLoginDTO();dto.setTermsVersionId(docs.get(0).versionId());dto.setPrivacyVersionId(docs.get(1).versionId());return dto;
    }
    @Test void absentPayloadDoesNotPretendHistoricalOrDraftAcceptance(){assertFalse(service.recordLogin("customer",7L,null));verifyNoInteractions(mapper);}
    @Test void loginRecordsTwoSeparateEventsWithServerIdentityOnly() {
        assertTrue(service.recordLogin("customer",7L,login("CUSTOMER")));
        verify(mapper).insertAcknowledgement(eq("customer"),eq(7L),anyString(),eq("TERMS_ACCEPTANCE"),eq("LOGIN_CLICK"),any());
        verify(mapper).insertAcknowledgement(eq("customer"),eq(7L),anyString(),eq("PRIVACY_NOTICE_ACKNOWLEDGEMENT"),eq("LOGIN_CLICK"),any());
    }
    @Test void draftsAndWrongAudienceWriteNothing() {
        var draft=new AgreementCatalogService(false,AgreementTestCatalog.CLOCK);var s=new AgreementAcknowledgementService(draft,mapper,AgreementTestCatalog.CLOCK);
        var dto=new AgreementLoginDTO();dto.setTermsVersionId(draft.catalog("CUSTOMER").documents().get(0).versionId());dto.setPrivacyVersionId(draft.catalog("CUSTOMER").documents().get(1).versionId());
        assertThrows(BusinessException.class,()->s.recordLogin("customer",7L,dto));
        assertThrows(BusinessException.class,()->service.recordLogin("customer",7L,login("STAFF")));verifyNoInteractions(mapper);
    }
    @Test void virtualOrUnknownActorCannotCreateEvidence(){assertThrows(BusinessException.class,()->service.recordLogin("staff",-1L,login("STAFF")));assertThrows(BusinessException.class,()->service.recordLogin("system",7L,null));verifyNoInteractions(mapper);}
    @Test void conflictingStoredBodyCannotBeOverwrittenOrAcknowledged() {
        when(mapper.documentForUpdate(anyString())).thenReturn(Map.of("bodyJson","conflicting"));
        assertThrows(BusinessException.class,()->service.recordLogin("customer",7L,login("CUSTOMER")));
        verify(mapper,never()).insertAcknowledgement(anyString(),anyLong(),anyString(),anyString(),anyString(),any());
    }
    @Test void explicitAcknowledgementUsesOwnCurrentSessionAndNoBlanketConsentEvent() {
        var dto=new AgreementAcknowledgementDTO();dto.setType("privacy");dto.setVersionId(catalog.catalog("CUSTOMER").documents().get(1).versionId());service.acknowledgeMy(dto);
        verify(mapper).insertAcknowledgement(eq("customer"),eq(7L),anyString(),eq("PRIVACY_NOTICE_ACKNOWLEDGEMENT"),eq("EXPLICIT_ACTION"),any());
    }
}
