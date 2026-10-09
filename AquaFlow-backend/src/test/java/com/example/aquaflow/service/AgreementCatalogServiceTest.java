package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgreementCatalogServiceTest {
    @Test void currentCustomerDraftLimitsConfirmationAndKeepsR2Readable() {
        var service = new AgreementCatalogService(false, AgreementTestCatalog.CLOCK);
        var current = service.catalog("CUSTOMER").documents().get(0);
        String text = current.sections().stream().map(section -> section.body()).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("免费原安排免再次确认"), "现行草稿必须准确说明免费原安排");
        assertTrue(text.contains("新增费用或水站提出的新安排"), "必要安排授权须限定范围");
        String previous = "customer-user-11c1aa1580cd7c9979a31a15676f604189c70ceab10a3828f0cb5598b5df4287";
        assertNotEquals(previous, current.versionId());
        var archived = service.document(previous);
        assertEquals("DRAFT", archived.status()); assertFalse(archived.active());
        assertEquals("11c1aa1580cd7c9979a31a15676f604189c70ceab10a3828f0cb5598b5df4287", archived.contentSha256());
        assertTrue(archived.sections().stream().anyMatch(section -> section.body().contains("客户安排确认")));
        assertEquals("DRAFT", current.status()); assertFalse(current.active()); assertFalse(service.catalog("CUSTOMER").enabled());
        assertThrows(BusinessException.class, () -> service.requireActive("CUSTOMER", "user", current.versionId()));
    }
    @Test void realPackagedDraftIsReadableButCannotBeActivatedOrAccepted() {
        var service=new AgreementCatalogService(false,AgreementTestCatalog.CLOCK);
        for(String audience:List.of("CUSTOMER","STAFF")) {
            var catalog=service.catalog(audience);assertFalse(catalog.enabled());assertEquals(2,catalog.documents().size());
            for(var doc:catalog.documents()) {
                assertEquals("DRAFT",doc.status());assertFalse(doc.active());assertTrue(doc.notice().contains("未启用"));
                assertEquals(doc,service.document(doc.versionId()));
                assertThrows(BusinessException.class,()->service.requireActive(audience,doc.type(),doc.versionId()));
            }
        }
        assertThrows(IllegalStateException.class,()->new AgreementCatalogService(true,AgreementTestCatalog.CLOCK));
    }
    @Test void aSwitchDoesNotApproveDraftsAndBodyTamperingFailsClosed() {
        var f=AgreementTestCatalog.fixture(false,false,Instant.EPOCH);
        assertThrows(IllegalStateException.class,()->f.catalog(true));
        String file=f.resources().keySet().iterator().next();f.resources().put(file,"{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->f.catalog(false));
    }
    @Test void approvedFutureDocumentIsNotYetActiveAndAbsentFlagCannotPublishIt() {
        var f=AgreementTestCatalog.fixture(true,false,Instant.parse("2026-10-09T00:00:00Z"));assertFalse(f.catalog(true).catalog("CUSTOMER").enabled());
        assertFalse(AgreementTestCatalog.fixture(true,false,Instant.EPOCH).catalog(false).catalog("CUSTOMER").enabled());
    }
    @Test void versionIsScopedToAudienceAndTypeAndOlderTextRemainsReadable() {
        var f=AgreementTestCatalog.fixture(true,true,Instant.EPOCH);var c=f.catalog(true);
        String older=AgreementTestCatalog.archivedVersion(f,"CUSTOMER","user");assertFalse(c.document(older).active());
        assertThrows(BusinessException.class,()->c.requireActive("CUSTOMER","user",older));
        var current=c.catalog("STAFF").documents().get(0);
        assertThrows(BusinessException.class,()->c.requireActive("CUSTOMER","user",current.versionId()));
        assertThrows(BusinessException.class,()->c.requireActive("STAFF","privacy",current.versionId()));
    }
    @Test void unknownScopeAndResourcePathCannotBecomeServerErrorsOrFileReads() {
        var c=AgreementTestCatalog.published();assertThrows(BusinessException.class,()->c.catalog(null));assertThrows(BusinessException.class,()->c.catalog("bad"));
        assertThrows(BusinessException.class,()->c.document("../../application-local.yml"));
    }
    @Test void missingReviewOrOperatorCannotPublishEvenWithMatchingHash() throws Exception {
        var f=AgreementTestCatalog.fixture(true,false,Instant.EPOCH);ObjectMapper json=new ObjectMapper();
        var index=json.readTree(f.index());((com.fasterxml.jackson.databind.node.ObjectNode)index.path("documents").get(0)).put("reviewApproved",false);
        assertThrows(IllegalStateException.class,()->new AgreementCatalogService(json.writeValueAsBytes(index),f.resources()::get,true,AgreementTestCatalog.CLOCK));
        String file=f.resources().keySet().iterator().next();var body=json.readTree(f.resources().get(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("operatorName","待补");byte[] bytes=json.writeValueAsBytes(body);f.resources().put(file,bytes);
        ((com.fasterxml.jackson.databind.node.ObjectNode)index.path("documents").get(0)).put("reviewApproved",true).put("sha256",AgreementCatalogService.sha256(bytes));
        assertThrows(IllegalStateException.class,()->new AgreementCatalogService(json.writeValueAsBytes(index),f.resources()::get,true,AgreementTestCatalog.CLOCK));
    }
    @Test void checkoutLineEndingsDoNotChangeTheCanonicalTextVersion() {
        var f=AgreementTestCatalog.fixture(true,false,Instant.EPOCH);var a=f.catalog(true);
        f.resources().replaceAll((name,bytes)->new String(bytes,StandardCharsets.UTF_8).replace("\n","\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(a.catalog("CUSTOMER"),f.catalog(true).catalog("CUSTOMER"));
    }
}
