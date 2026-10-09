package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="app.account-data-requests.enabled=false")
class AccountDataRequestDisabledIntegrationTest extends AbstractIntegrationTest {
    @Test void defaultClosedIntakeRejectsNewRequestsWithoutChangingAccountAndKeepsOwnCheckAvailable() {
        long customer=createCustomer("未开放本人","request-disabled");String token=customerToken(customer);var before=jdbc.queryForList("select * from customer");
        var options=get("/api/account/data-requests/options",token);assertEquals(0,options.code(),options.toString());assertFalse(options.data().path("intakeEnabled").asBoolean());assertTrue(options.data().path("intakeContact").isNull());
        assertEquals(1,post("/api/account/data-requests",token,"{\"requestType\":\"CLOSURE\",\"idempotencyKey\":\"disabled-test\"}").code());assertEquals(0,intOf("select count(*) from account_data_request"));assertEquals(before,jdbc.queryForList("select * from customer"));
        assertEquals(0,get("/api/customer/account/closure-check",token).code());assertEquals(0,get("/api/account/data-requests/my",token).data().path("items").size());
    }
}
