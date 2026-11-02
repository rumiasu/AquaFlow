package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/MySQL verifies independent permissions, concurrency and atomic evidence. */
@TestPropertySource(properties={"app.payment.mock-wechat-pay=false","aquaflow.barrel.independent-rights-enabled=true"})
class CaseHandlingIntegrationTest extends AbstractIntegrationTest {
 long station,customer,feedback,exception;String manager,delivery,stranger,owner,other;
 void seed(){
  station=createStation("核实测试站");long second=createStation("无关测试站");
  manager=staffToken(createStaff("合成站长","STATION_MANAGER",station,1),"STATION_MANAGER",station);
  stranger=staffToken(createStaff("他站长","STATION_MANAGER",second,1),"STATION_MANAGER",second);
  delivery=staffToken(createStaff("合成配送","DELIVERY",station,1),"DELIVERY",station);
  customer=createCustomer("合成顾客","synthetic-case-owner");owner=customerToken(customer);other=customerToken(createCustomer("其他顾客","synthetic-case-other"));
  createCustomerStationConfig(customer,station,1);
  feedback=insert("insert into feedback(customer_id,category,content,anonymous,create_time) values(?, '配送服务','合成反馈',1,now())",customer);
  long product=createProduct("合成桶水",1,"20","50",0,"0"),address=createAddress(customer,"合成地址"),order=createOrder(customer,address,station,product,3,0);
  exception=insert("insert into order_barrel_exception(order_id,customer_id,station_id,category,delivery_qty,return_qty,discrepancy,status) values(?,?,?,'OTHER',2,2,0,'STAFF_RECORDED')",order,customer,station);
 }
 String body(String key,long version){return "{\"expectedVersion\":"+version+",\"idempotencyKey\":\""+key+"\",\"reason\":\"已核实，记录处理结果\"}";}
 @Test void replyIsCustomerVisibleAndAnonymousIdentityStaysHidden(){seed();String url="/api/feedback/"+feedback;
  assertEquals(0,post(url+"/reply",manager,body("reply-one",0)).code());
  assertEquals(1,get(url+"/handling",other).code());assertEquals(1,get(url+"/handling",stranger).code());assertEquals(1,post(url+"/reply",delivery,body("forbidden",1)).code());
  var own=get(url+"/handling",owner);assertEquals(0,own.code());assertEquals(1,own.data().path("actions").size());assertFalse(own.data().has("customerId"));
  var list=get("/api/feedback/customers",manager);assertTrue(list.data().get(0).path("customerId").isNull());
  assertEquals(0,post(url+"/reply",manager,body("reply-one",0)).code());assertEquals(1,jdbc.queryForObject("select count(*) from case_handling_action",Integer.class));
  assertEquals(1,post(url+"/close",manager,body("stale-close",0)).code());assertEquals(0,post(url+"/close",manager,body("close-one",1)).code());
  assertEquals(1,post(url+"/reply",manager,body("closed-reply",2)).code());assertEquals(2,get(url+"/handling",owner).data().path("actions").size());
 }
 @Test void exceptionResolutionCannotConsumePendingCompensation(){seed();String url="/api/manager/exceptions/"+exception;
  jdbc.update("update order_barrel_exception set suggested_cash_amount=10 where id=?",exception);
  assertEquals(1,post(url+"/resolve",manager,body("must-not-close",0)).code());assertEquals("STAFF_RECORDED",jdbc.queryForObject("select status from order_barrel_exception where id=?",String.class,exception));
  jdbc.update("update order_barrel_exception set suggested_cash_amount=0 where id=?",exception);
  assertEquals(1,post(url+"/resolve",stranger,body("cross-station",0)).code());assertEquals(1,post(url+"/resolve",delivery,body("wrong-role",0)).code());
  assertEquals(0,post(url+"/resolve",manager,body("verified-one",0)).code());assertEquals(0,post(url+"/resolve",manager,body("verified-one",0)).code());
  assertEquals("VERIFIED",jdbc.queryForObject("select status from order_barrel_exception where id=?",String.class,exception));assertEquals(1,jdbc.queryForObject("select count(*) from case_handling_action",Integer.class));
  assertEquals(0,jdbc.queryForObject("select count(*) from deposit_record",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from payment_record",Integer.class));
 }
 @Test void competingFeedbackVersionsAllowOnlyOne(){seed();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{var a=pool.submit(()->{start.await();return post("/api/feedback/"+feedback+"/reply",manager,body("race-a",0)).code();});var b=pool.submit(()->{start.await();return post("/api/feedback/"+feedback+"/close",manager,body("race-b",0)).code();});start.countDown();assertEquals(1,a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("select count(*) from case_handling_action",Integer.class));}
  catch(Exception failure){throw new RuntimeException(failure);}finally{pool.shutdownNow();}
 }
 @Test void evidenceSqlFailureRollsBackExceptionStatus(){seed();
  jdbc.execute("create trigger case_handling_fail before insert on case_handling_action for each row SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic evidence failure'");
  try{assertNotEquals(0,post("/api/manager/exceptions/"+exception+"/resolve",manager,body("failure-rollback",0)).code());assertEquals("STAFF_RECORDED",jdbc.queryForObject("select status from order_barrel_exception where id=?",String.class,exception));assertEquals(0,jdbc.queryForObject("select count(*) from case_handling_action",Integer.class));}
  finally{jdbc.execute("drop trigger case_handling_fail");}
 }
}
