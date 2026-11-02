package com.example.aquaflow.service;

import com.example.aquaflow.dto.ExceptionCloseoutDTO;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaseHandlingServiceTest {
 CaseHandlingMapper m; FeedbackMapper f; OrderBarrelExceptionMapper e; CaseHandlingService service;
 @BeforeEach void setup(){m=mock(CaseHandlingMapper.class);f=mock(FeedbackMapper.class);e=mock(OrderBarrelExceptionMapper.class);service=new CaseHandlingService(m,f,e);when(m.schemaReady()).thenReturn(1);manager(2);}
 @AfterEach void clear(){AuthContext.clear();}
 void manager(long station){AuthContext.set(new AuthContext.AuthUser(7L,"staff","STATION_MANAGER",station));}
 ExceptionCloseoutDTO input(){var d=new ExceptionCloseoutDTO();d.setExpectedVersion(0L);d.setReason("已核实现场，无需补偿");d.setIdempotencyKey("synthetic-case-key");return d;}
 OrderBarrelException exception(){var x=new OrderBarrelException();x.setId(1L);x.setStationId(2L);x.setStatus("STAFF_RECORDED");x.setDiscrepancy(0);when(e.getByIdForUpdate(1L)).thenReturn(x);when(e.getById(1L)).thenReturn(x);return x;}
 Feedback feedback(){var x=new Feedback();x.setId(1L);x.setCustomerId(42L);when(f.getById(1L)).thenReturn(x);when(m.lockFeedback(1L)).thenReturn(x);when(m.related(42L,2L)).thenReturn(1);return x;}
 Map<String,Object> receipt(String action){var d=ExceptionActionEvidence.input(input(),action);return new HashMap<>(Map.of("kind",action.equals("VERIFY")?"EXCEPTION":"FEEDBACK","caseId",1L,"version",1L,"action",action,"requestDigest",d.digest()));}
 void appendReady(String kind,String action){when(m.receipt(eq(kind),eq(1L),eq(2L),eq(7L),anyString())).thenReturn(null,receipt(action));when(m.append(eq(kind),eq(1L),eq(2L),eq(7L),eq(1L),eq(action),anyString(),anyString(),anyString())).thenReturn(1);}
 @Test void resolvesWithoutLedgerOrPaymentWrites(){exception();appendReady("EXCEPTION","VERIFY");when(e.updateDecisionIf(1L,"STAFF_RECORDED","VERIFY",null,null,null,null,input().getReason(),"VERIFIED")).thenReturn(1);assertEquals(1L,service.resolve(1L,input()).get("version"));verify(e).updateDecisionIf(1L,"STAFF_RECORDED","VERIFY",null,null,null,null,input().getReason(),"VERIFIED");}
 @Test void deliveryCannotResolve(){AuthContext.set(new AuthContext.AuthUser(7L,"staff","DELIVERY",2L));assertThrows(RuntimeException.class,()->service.resolve(1L,input()));verifyNoInteractions(e);}
 @Test void otherStationCannotResolve(){exception();manager(3);assertThrows(RuntimeException.class,()->service.resolve(1L,input()));verify(m,never()).append(any(),any(),any(),any(),anyLong(),any(),any(),any(),any());}
 @Test void allCompensationAndPhysicalPendingCasesReject(){for(int n=0;n<8;n++){var x=exception();if(n==0)x.setSuggestedTicketQty(1);if(n==1)x.setSuggestedCashAmount(BigDecimal.ONE);if(n==2)x.setRefundTicketQty(1);if(n==3)x.setRefundCashAmount(BigDecimal.ONE);if(n==4)x.setDiscrepancy(1);if(n==5)x.setWaterOwed(1);if(n==6)x.setStatus("MANAGER_APPROVED");if(n==7)x.setCategory("CUSTOMER_REFUSE");assertThrows(RuntimeException.class,()->service.resolve(1L,input()));}}
 @Test void duplicateResolveReturnsOriginalBeforeTerminalCheck(){exception().setStatus("VERIFIED");when(m.receipt(eq("EXCEPTION"),eq(1L),eq(2L),eq(7L),anyString())).thenReturn(receipt("VERIFY"));assertEquals("VERIFY",service.resolve(1L,input()).get("action"));verify(e,never()).updateDecisionIf(any(),any(),any(),any(),any(),any(),any(),any(),any());}
 @Test void reusedKeyCannotChangeReason(){exception();when(m.receipt(eq("EXCEPTION"),eq(1L),eq(2L),eq(7L),anyString())).thenReturn(receipt("VERIFY"));var d=input();d.setReason("另一裁定");assertThrows(RuntimeException.class,()->service.resolve(1L,d));}
 @Test void staleVersionRejects(){exception();when(m.history("EXCEPTION",1L,2L)).thenReturn(List.of(Map.of("version",1L)));assertThrows(RuntimeException.class,()->service.resolve(1L,input()));}
 @Test void failedCasDoesNotAppendReceipt(){exception();assertThrows(RuntimeException.class,()->service.resolve(1L,input()));verify(m,never()).append(any(),any(),any(),any(),anyLong(),any(),any(),any(),any());}
 @Test void feedbackReplyHasVersionedReceipt(){feedback();appendReady("FEEDBACK","REPLY");assertEquals("REPLY",service.feedbackAct(1L,input(),false).get("action"));}
 @Test void unrelatedStationCannotReply(){feedback();manager(3);assertThrows(RuntimeException.class,()->service.feedbackAct(1L,input(),false));}
 @Test void customerCanReadOnlyOwnReplyThread(){feedback();AuthContext.set(new AuthContext.AuthUser(42L,"customer","CUSTOMER",null));var view=service.feedbackView(1L);assertFalse(view.containsKey("customerId"));assertFalse(view.containsKey("closed"));assertEquals(0L,view.get("version"));assertEquals(false,view.get("canReply"));AuthContext.set(new AuthContext.AuthUser(43L,"customer","CUSTOMER",null));assertThrows(RuntimeException.class,()->service.feedbackView(1L));}
 @Test void refundFeedbackCannotUseOrdinaryWorkflow(){feedback().setRefundType("ORDER_PAYMENT");assertThrows(RuntimeException.class,()->service.feedbackAct(1L,input(),true));}
 @Test void closedFeedbackRejectsNewActionButAcceptsExactRetry(){feedback();when(m.history("FEEDBACK",1L,2L)).thenReturn(List.of(Map.of("version",1L,"action","CLOSE")));assertThrows(RuntimeException.class,()->service.feedbackAct(1L,input(),false));when(m.receipt(eq("FEEDBACK"),eq(1L),eq(2L),eq(7L),anyString())).thenReturn(receipt("CLOSE"));assertEquals("CLOSE",service.feedbackAct(1L,input(),true).get("action"));}
 @Test void missingMigrationRefusesBeforeWrites(){when(m.schemaReady()).thenReturn(0);assertThrows(RuntimeException.class,()->service.resolve(1L,input()));verifyNoInteractions(e);}
 @Test void invalidRequestAlphabetIsBusinessRejection(){exception();var d=input();d.setIdempotencyKey("非法编号");assertThrows(com.example.aquaflow.exception.BusinessException.class,()->service.resolve(1L,d));verify(m,never()).append(any(),any(),any(),any(),anyLong(),any(),any(),any(),any());}
 @Test void exceptionViewExplainsPhysicalAndCompensationBlockWithoutReadingCustomerProfiles(){var x=exception();x.setOrderId(91L);x.setCategory("OTHER");x.setStaffNote("现场说明");x.setWaterOwed(1);var view=service.exceptionView(1L);assertEquals(false,view.get("canResolve"));assertEquals(91L,view.get("orderId"));assertEquals("现场说明",view.get("subjectText"));assertTrue(String.valueOf(view.get("blockReason")).contains("差异"));assertFalse(view.containsKey("customerId"));verifyNoInteractions(f);}
 @Test void anonymousFeedbackSubjectContainsOnlyOriginalContentAndCategory(){var x=feedback();x.setAnonymous(true);x.setContent("匿名原反馈");x.setCategory("服务");x.setContact("synthetic-private-contact");var view=service.feedbackView(1L);assertEquals("匿名原反馈",view.get("subjectText"));assertEquals("服务",view.get("subjectCategory"));assertFalse(view.containsKey("customerId"));assertFalse(view.containsKey("contact"));assertFalse(view.containsKey("customerName"));}
}
