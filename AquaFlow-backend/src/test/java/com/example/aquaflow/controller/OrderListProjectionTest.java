package com.example.aquaflow.controller;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.*;
import com.example.aquaflow.service.impl.OrderServiceImpl;
import com.example.aquaflow.util.AuthContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderListProjectionTest {
 @AfterEach void clear(){AuthContext.clear();}
 private Orders order(){Orders o=new Orders();o.setId(81L);o.setCustomerId(7L);o.setStationId(11L);o.setStatus(2);o.setPaymentMethod(1);o.setPaymentStatus(1);return o;}
 @Test void listProjectsCurrentPaymentCapabilityForOwnCustomer(){
  AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null));
  OrderService service=mock(OrderService.class);PaymentService payments=mock(PaymentService.class);Orders row=order();
  when(service.list(null,7L,null,null,null,200,0,false)).thenReturn(List.of(row));when(payments.canSelfPay(row)).thenReturn(true);
  OrderController c=new OrderController();ReflectionTestUtils.setField(c,"orderService",service);ReflectionTestUtils.setField(c,"paymentService",payments);
  c.list(null,999L,null,null,null,null,null);assertTrue(row.getCanRepay());verify(service).list(null,7L,null,null,null,200,0,false);
 }
 @Test void staffListDoesNotReceiveCustomerPaymentCapability(){
  AuthContext.set(new AuthContext.AuthUser(9L,"staff","STATION_MANAGER",11L));OrderService service=mock(OrderService.class);PaymentService payments=mock(PaymentService.class);
  when(service.list(11L,null,null,null,null,200,0,true)).thenReturn(List.of(order()));OrderController c=new OrderController();ReflectionTestUtils.setField(c,"orderService",service);ReflectionTestUtils.setField(c,"paymentService",payments);
  c.list(999L,null,null,null,null,null,null);verifyNoInteractions(payments);
 }
 @Test void customerListIncludesPendingCancellationAndBlocksDuplicate(){checkCancel("PENDING",false);}
 @Test void customerListKeepsResolvedRequestResultWithoutBlockingNewRequest(){checkCancel("REJECTED",true);}
 private void checkCancel(String state,boolean allowed){
  AuthContext.set(new AuthContext.AuthUser(7L,"customer",null,null));OrderServiceImpl s=new OrderServiceImpl();OrderMapper orders=mock(OrderMapper.class);OrderTransferMapper transfers=mock(OrderTransferMapper.class);OrderCancelResultMapper results=mock(OrderCancelResultMapper.class);
  ReflectionTestUtils.setField(s,"orderMapper",orders);ReflectionTestUtils.setField(s,"cancelRequests",transfers);ReflectionTestUtils.setField(s,"cancelResults",results);
  Orders row=order();OrderTransfer t=new OrderTransfer();t.setId(91L);t.setStatus(state);when(orders.list(null,7L,null,null,null,20,0,false)).thenReturn(List.of(row));when(transfers.latestCustomerCancelRequest(81L)).thenReturn(t);
  s.list(null,7L,null,null,null,20,0,false);assertNotNull(row.getCustomerCancelRequest());assertEquals(state,row.getCustomerCancelRequest().getStatus());assertEquals(allowed,row.getCanCancel());
 }
}
