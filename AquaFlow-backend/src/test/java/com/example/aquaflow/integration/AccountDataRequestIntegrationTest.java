package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic intake contact is test-only. There is no export/delete or credential command. */
@TestPropertySource(properties={"app.account-data-requests.enabled=true","app.account-data-requests.intake-owner=synthetic-test-owner","app.account-data-requests.intake-contact=synthetic-test-contact"})
class AccountDataRequestIntegrationTest extends AbstractIntegrationTest {
    static final String PATH="/api/account/data-requests";
    Api submit(String token,String type,String key,String note) {
        try{return post(PATH,token,om.writeValueAsString(Map.of("requestType",type,"idempotencyKey",key,"note",note)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    Map<String,Object> business() {
        var rows=new LinkedHashMap<String,Object>();for(String table:List.of("customer","staff","user_token","orders","payment_record","customer_deposit_account","deposit_record","ticket_lot","customer_barrel_lot","refund_dispute","customer_refusal_case","customer_refusal_resolution"))rows.put(table,jdbc.queryForList("select * from "+table));return rows;
    }
    @Test void unresolvedAccountCanFileClosureButRequestDoesNotDeleteOrChangeBusinessCredentials() {
        long customer=createCustomer("申请本人","request-own"),station=createStation("申请资产站");String token=customerToken(customer);
        createDepositBalance(customer,station,"30.00");var before=business();var first=submit(token,"CLOSURE","request-closure","核实账户资料");assertEquals(0,first.code(),first.toString());
        long id=first.data().path("id").asLong();assertEquals("SUBMITTED",first.data().path("status").asText());
        var stored=jdbc.queryForList("select * from account_data_request");assertEquals(first.data(),submit(token,"CLOSURE","request-closure","核实账户资料").data());assertEquals(stored,jdbc.queryForList("select * from account_data_request"));
        assertEquals(1,submit(token,"EXPORT","request-closure","核实账户资料").code());assertEquals(1,submit(token,"CLOSURE","request-closure","新内容").code());
        var detail=get(PATH+"/"+id,token);assertEquals(0,detail.code(),detail.toString());assertFalse(detail.data().path("closureCheck").path("clear").asBoolean());assertEquals(before,business());
        assertFalse(detail.data().has("canDelete"));assertEquals(0,submit(token,"DELETION","request-delete","").code());assertEquals(0,submit(token,"EXPORT","request-export","").code());assertEquals(before,business());
    }
    @Test void onlyOwnPositiveCustomerOrStaffIdentityCanReadOrSubmit() {
        long customer=createCustomer("申请本人","request-own"),other=createCustomer("申请他人","request-other");String token=customerToken(customer);long id=submit(token,"ACCESS","own-key","").data().path("id").asLong();
        assertEquals(1,get(PATH+"/"+id,customerToken(other)).code());assertEquals(0,get(PATH+"/my",customerToken(other)).data().path("items").size());
        assertEquals(1,get(PATH+"/my?customerId="+customer,token).code());assertEquals(1,get(PATH+"/options?stationId=1",token).code());assertEquals(1,get(PATH+"/"+id+"?actorId="+customer,token).code());
        assertNotEquals(0,submit(null,"ACCESS","anon-key","").code());assertEquals(1,submit(unselectedStaffToken(-7,"request-unselected"),"ACCESS","virtual-key","").code());
        long station=createStation("申请员工站"),staff=createStaff("员工本人","DELIVERY",station,1);String employee=staffToken(staff,"DELIVERY",station);
        assertEquals(1,get(PATH+"/"+id,employee).code());assertFalse(get(PATH+"/options",employee).data().path("closureCheckSupported").asBoolean());
        long employeeRequest=submit(employee,"CLOSURE","own-key","").data().path("id").asLong();var detail=get(PATH+"/"+employeeRequest,employee);
        assertTrue(detail.data().path("closureCheck").isNull(),detail.toString());assertTrue(detail.data().path("checkNotice").asText().contains("未完成员工清结检查"),detail.toString());assertEquals(1,get(PATH+"/"+employeeRequest,token).code());
    }
    @Test void concurrentRetryStoresOneRequestAndPaginationExposesOlderRows() {
        long customer=createCustomer("并发申请人","request-race");String token=customerToken(customer);
        var a=CompletableFuture.supplyAsync(()->submit(token,"CORRECTION","concurrent-key","同一份说明"));var b=CompletableFuture.supplyAsync(()->submit(token,"CORRECTION","concurrent-key","同一份说明"));
        var ra=a.join();var rb=b.join();assertEquals(0,ra.code(),ra.toString());assertEquals(0,rb.code(),rb.toString());assertEquals(ra.data().path("id"),rb.data().path("id"));assertEquals(1,intOf("select count(*) from account_data_request"));
        for(int i=0;i<50;i++)assertEquals(0,submit(token,"ACCESS","page-"+i,"").code());
        var first=get(PATH+"/my",token);assertEquals(50,first.data().path("items").size());long before=first.data().path("nextBeforeId").asLong();assertTrue(before>0);
        var older=get(PATH+"/my?beforeId="+before,token);assertEquals(1,older.data().path("items").size());assertTrue(older.data().path("nextBeforeId").isNull());
        assertEquals(1,get(PATH+"/my?beforeId=0",token).code());
    }
}
