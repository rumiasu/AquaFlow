package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="app.agreements.formal-enabled=false")
class AgreementDraftIntegrationTest extends AbstractIntegrationTest {
    Api post(String path,String token,Map<String,?> body) {
        try{return super.post(path,token,om.writeValueAsString(body));}catch(Exception e){throw new IllegalStateException(e);}
    }
    @Test void publicDraftReadingDoesNotCreateAcceptanceAndWriteRoutesRemainAuthenticated() {
        Api catalog=get("/api/agreements/current?audience=CUSTOMER",null);assertEquals(0,catalog.code(),catalog.toString());assertFalse(catalog.data().path("enabled").asBoolean());
        String version=catalog.data().path("documents").get(0).path("versionId").asText();
        assertEquals(0,get("/api/agreements/documents/"+version,null).code());assertEquals(1,get("/api/agreements/current?audience=bad",null).code());
        assertNotEquals(0,post("/api/agreements/acknowledgements",null,Map.of("type","user","versionId",version)).code());
        long customer=createCustomer("草稿本人","draft-own");String token=customerToken(customer);
        assertEquals(1,post("/api/agreements/acknowledgements",token,Map.of("type","user","versionId",version)).code());
        assertEquals(1,get("/api/agreements/acknowledgements/my?customerId="+customer,token).code());
        assertEquals(1,get("/api/agreements/acknowledgements/my",unselectedStaffToken(-9,"draft-unselected")).code());
        assertEquals(0,intOf("select count(*) from agreement_document"));assertEquals(0,intOf("select count(*) from agreement_acknowledgement"));
    }
    @Test void draftLoginEvidenceIsRejectedBeforeExternalWeChatAndNoAccountOrTokenIsCreated() {
        var docs=get("/api/agreements/current?audience=CUSTOMER",null).data().path("documents");
        var before=jdbc.queryForList("select * from user_token");
        Api result=post("/api/auth/wx-login",null,Map.of("code","synthetic-invalid-code","agreement",Map.of("termsVersionId",docs.get(0).path("versionId").asText(),"privacyVersionId",docs.get(1).path("versionId").asText())));
        assertEquals(1,result.code(),result.toString());assertTrue(result.message().contains("正式协议尚未启用"));
        assertEquals(0,intOf("select count(*) from customer"));assertEquals(before,jdbc.queryForList("select * from user_token"));
    }
}
