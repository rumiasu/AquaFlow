package com.example.aquaflow.service;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.controller.BarrelController;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 纯事实 mock：不启 Spring、不连接数据库、不执行退款；真实渠道与SQL执行另验。 */
class ApprovalRefundEligibilityReadOnlyTest {
    private ApprovedBarrelReturnService service;
    private BarrelBusinessPolicy policy;
    private BarrelRecordMapper records;
    private BarrelReturnDetailMapper details;
    private CustomerBarrelLotMapper lots;
    private CustomerDepositAccountMapper accounts;
    private BarrelBusinessMapper business;
    private OrderBarrelPurchaseService combined;
    private PaymentRecordMapper payments;
    private OrderMapper orders;
    private BarrelLedgerService ledger;
    private CustomerRiskService risk;
    private BarrelRecordLotMapper recordLots;
    private ConsumptionRefundMapper paymentLocks;
    private DepositRecordService deposits;
    private BarrelRecord record;
    private BarrelReturnDetail detail;
    private BarrelRightPurchase purchase;
    private PaymentRecord original;
    private PlatformTransactionManager transactions;
    private TransactionStatus transaction;

    @BeforeEach void setup() {
        service=new ApprovedBarrelReturnService();
        transactions=mock(PlatformTransactionManager.class); transaction=new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        ReflectionTestUtils.setField(service,"transactionManager",transactions);
        policy=mock(BarrelBusinessPolicy.class); records=mock(BarrelRecordMapper.class); details=mock(BarrelReturnDetailMapper.class);
        lots=mock(CustomerBarrelLotMapper.class); accounts=mock(CustomerDepositAccountMapper.class); business=mock(BarrelBusinessMapper.class);
        combined=mock(OrderBarrelPurchaseService.class); payments=mock(PaymentRecordMapper.class); orders=mock(OrderMapper.class);
        ledger=mock(BarrelLedgerService.class); risk=mock(CustomerRiskService.class); recordLots=mock(BarrelRecordLotMapper.class);
        paymentLocks=mock(ConsumptionRefundMapper.class); deposits=mock(DepositRecordService.class);
        Map<String,Object> fields=new HashMap<>();
        fields.put("policy",policy); fields.put("recordMapper",records); fields.put("detailMapper",details); fields.put("refundLots",lots);
        fields.put("refundAccounts",accounts); fields.put("businessMapper",business); fields.put("orderPurchases",combined);
        fields.put("paymentMapper",payments); fields.put("orderMapper",orders); fields.put("ledger",ledger); fields.put("risk",risk);
        fields.put("recordLotMapper",recordLots); fields.put("paymentLocks",paymentLocks); fields.put("depositService",deposits);
        ProductMapper products=mock(ProductMapper.class); fields.put("productMapper",products);
        fields.forEach((name,value)->ReflectionTestUtils.setField(service,name,value));
        record=new BarrelRecord(); record.setId(77L); record.setType(2); record.setStatus(2); record.setCustomerId(7L);
        record.setStationId(1L); record.setProductId(10L); record.setQuantity(2); record.setDepositRefund(new BigDecimal("60"));
        detail=new BarrelReturnDetail(); detail.setRecordId(77L); detail.setStatus("RECEIVED"); detail.setRequiredBarrels(2);
        when(policy.hasSchema()).thenReturn(true); when(records.getById(77L)).thenReturn(record); when(details.get(77L)).thenReturn(detail);
        when(lots.listAvailable(7L,1L,10L)).thenReturn(List.of(lot(50L,2,"30")));
        when(details.heldLots(77L)).thenReturn(List.of(Map.of("lotId",50L,"qty",2,"amount",new BigDecimal("60"))));
        purchase=new BarrelRightPurchase(); purchase.setId(90L); purchase.setCustomerId(7L); purchase.setStationId(1L); purchase.setProductId(10L);
        purchase.setLotId(50L); purchase.setPaymentId(100L); when(business.purchaseByLot(50L)).thenReturn(purchase);
        original=new PaymentRecord(); original.setId(100L); original.setCustomerId(7L); original.setStationId(1L); original.setPaymentMethod(PayMethod.CASH);
        when(payments.getById(100L)).thenReturn(original);
        CustomerDepositAccount account=new CustomerDepositAccount(); account.setBalance(new BigDecimal("1000")); when(accounts.getByCustomerAndStation(7L,1L)).thenReturn(account);
        Product product=new Product(); product.setName("真实商品"); when(products.getById(10L)).thenReturn(product);
    }
    private static CustomerBarrelLot lot(Long id,int qty,String price) {
        CustomerBarrelLot value=new CustomerBarrelLot(); value.setId(id); value.setRemainQty(qty); value.setUnitPrice(new BigDecimal(price)); return value;
    }
    private Map<String,Object> read() { return service.refundEligibility(77L,1L); }
    private void unavailable(Map<String,Object> value,String reason) {
        assertEquals(false,value.get("available")); assertTrue(String.valueOf(value.get("reason")).contains(reason),String.valueOf(value));
    }
    @Test void cashSourceIsReadOnlyAndManagerScoped() throws Exception {
        Map<String,Object> result=read(); assertEquals(true,result.get("available")); assertEquals("CASH",result.get("channel"));
        assertEquals(new BigDecimal("60"),result.get("refundAmount")); assertEquals("真实商品",result.get("productName"));
        verify(records).getById(77L); verifyNoMoreInteractions(records);
        verify(payments).getById(100L); verifyNoMoreInteractions(payments); verifyNoInteractions(recordLots,paymentLocks,deposits);
        ArgumentCaptor<TransactionDefinition> definition=ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture()); assertTrue(definition.getValue().isReadOnly());
        verify(transactions).commit(transaction); verify(transactions,never()).rollback(any());
        assertNull(ApprovedBarrelReturnService.class.getMethod("refundEligibility",Long.class,Long.class).getAnnotation(Transactional.class));
        assertArrayEquals(new String[]{"STATION_MANAGER"},BarrelController.class.getMethod("refundEligibility",Long.class).getAnnotation(RequireRole.class).value());
    }
    @Test void onlineUnavailableNeverOffersCashReplacement() {
        original.setPaymentMethod(PayMethod.WECHAT); Map<String,Object> result=read();
        unavailable(result,"线上原渠道退款尚不可用"); assertEquals("ONLINE",result.get("channel"));
    }
    @Test void mockOnlineCapabilityUsesOriginalOnlineChannel() {
        original.setPaymentMethod(PayMethod.WECHAT); ReflectionTestUtils.setField(service,"mockWechatPay",true);
        assertEquals(true,read().get("available")); assertEquals("ONLINE",read().get("channel"));
    }
    @Test void missingKnownOriginalAndUnsupportedChannelRemainUnknown() {
        when(payments.getById(100L)).thenReturn(null); unavailable(read(),"原收款凭据未能核对");
        when(payments.getById(100L)).thenReturn(original); original.setPaymentMethod(PayMethod.TICKET); unavailable(read(),"原收款方式尚不能办理");
    }
    @Test void historicalHeldLotWithoutOnlineProofKeepsExistingCashPath() {
        when(business.purchaseByLot(50L)).thenReturn(null);
        Map<String,Object> result=read(); assertEquals(true,result.get("available")); assertEquals("CASH",result.get("channel")); assertEquals(true,result.get("historicalSource"));
    }
    @Test void legacyUsesSharedActualAllocationPreviewInsteadOfRequestEstimate() {
        when(details.get(77L)).thenReturn(null);
        BarrelLedgerService.LotConsumption amount=new BarrelLedgerService.LotConsumption(); amount.setAmount(new BigDecimal("80"));
        when(ledger.previewRefundLots(7L,1L,10L,2)).thenReturn(amount);
        Map<String,Object> result=read(); assertEquals(true,result.get("available")); assertEquals(new BigDecimal("80"),result.get("refundAmount"));
        verify(ledger).previewRefundLots(7L,1L,10L,2); verifyNoInteractions(lots); verify(details,never()).heldLots(anyLong());
    }
    @Test void legacyUnavailableAllocationCannotPromptCashHandover() {
        when(details.get(77L)).thenReturn(null); when(ledger.previewRefundLots(7L,1L,10L,2)).thenThrow(new BusinessException("该部分权益正在配送或退还申请中，不能再次扣减"));
        unavailable(read(),"正在配送或退还申请中"); verifyNoInteractions(payments);
        verify(transactions).rollback(transaction); verify(transactions,never()).commit(any());
        // 已有外层事务时拒绝继续伪装成成功响应，避免外层提交再报 UnexpectedRollbackException。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(BusinessException.class,this::read); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void noHandoverAndTerminalsCannotRefund() {
        for (String state:List.of("APPLIED","APPROVED","REFUNDED","REJECTED","WITHDRAWN")) { detail.setStatus(state); unavailable(read(),"请先完成"); }
        verifyNoInteractions(payments,lots);
    }
    @Test void crossStationAndNonReturnCannotReadSourceDetails() {
        assertThrows(BusinessException.class,()->service.refundEligibility(77L,2L)); record.setType(8);
        assertThrows(BusinessException.class,this::read); verifyNoInteractions(details,payments,lots);
    }
    @Test void insufficientHeldLotOrBalanceKeepsRefundPending() {
        when(lots.listAvailable(7L,1L,10L)).thenReturn(List.of(lot(50L,1,"30"))); unavailable(read(),"预留退款批次不足");
        when(lots.listAvailable(7L,1L,10L)).thenReturn(List.of(lot(50L,2,"30"))); when(accounts.getByCustomerAndStation(7L,1L)).thenReturn(null);
        unavailable(read(),"押金账户余额不足");
    }
    @Test void mixedOriginalChannelsCannotBePresentedAsCashOnly() {
        when(lots.listAvailable(7L,1L,10L)).thenReturn(List.of(lot(50L,1,"30"),lot(51L,1,"30")));
        when(details.heldLots(77L)).thenReturn(List.of(Map.of("lotId",50L,"qty",1,"amount",new BigDecimal("30")),Map.of("lotId",51L,"qty",1,"amount",new BigDecimal("30"))));
        BarrelRightPurchase online=new BarrelRightPurchase(); online.setId(91L); online.setCustomerId(7L); online.setStationId(1L); online.setProductId(10L); online.setLotId(51L); online.setPaymentId(101L);
        when(business.purchaseByLot(51L)).thenReturn(online);
        PaymentRecord second=new PaymentRecord(); second.setId(101L); second.setCustomerId(7L); second.setStationId(1L); second.setPaymentMethod(PayMethod.WECHAT); when(payments.getById(101L)).thenReturn(second);
        unavailable(read(),"不同原收款方式");
    }
    private OrderBarrelPurchase useCombinedSource() {
        when(business.purchaseByLot(50L)).thenReturn(null);
        OrderBarrelPurchase value=new OrderBarrelPurchase(); value.setId(99L); value.setCustomerId(7L); value.setStationId(1L); value.setProductId(10L); value.setLotId(50L); value.setOrderId(200L); value.setPaymentId(100L);
        when(combined.byLot(50L)).thenReturn(value);
        Orders order=new Orders(); order.setId(200L); order.setCustomerId(7L); order.setStationId(1L); order.setSettleStationId(2L); order.setDeliveryStationId(2L); when(orders.getById(200L)).thenReturn(order);
        original.setOrderId(200L); original.setStationId(2L); return value;
    }
    @Test void combinedDispatchCashOriginalAtSettlementStationRemainsValid() {
        useCombinedSource(); Map<String,Object> result=read(); assertEquals(true,result.get("available")); assertEquals("CASH",result.get("channel"));
    }
    @Test void combinedForeignAssetOrUnrelatedPaymentCannotAuthorizeHandover() {
        OrderBarrelPurchase value=useCombinedSource(); value.setStationId(2L); unavailable(read(),"随单押金原款关联");
        value.setStationId(1L); original.setOrderId(201L); unavailable(read(),"随单押金原款关联");
    }
    @Test void standaloneForeignCollectionIsNotTreatedAsCombinedDispatch() {
        original.setStationId(2L); unavailable(read(),"独立押金原款关联");
    }
    @Test void explicitReadOnlyPreviewSkipsHeldCheapLotAndPreservesPriorDryRunLockChoice() {
        BarrelLedgerService actual=new BarrelLedgerService(); BarrelBusinessPolicy livePolicy=mock(BarrelBusinessPolicy.class);
        CustomerBarrelLotMapper liveLots=mock(CustomerBarrelLotMapper.class); BarrelBusinessMapper liveBusiness=mock(BarrelBusinessMapper.class);
        BarrelReturnDetailMapper liveDetails=mock(BarrelReturnDetailMapper.class); CustomerBarrelOverMapper over=mock(CustomerBarrelOverMapper.class);
        ReflectionTestUtils.setField(actual,"businessPolicy",livePolicy); ReflectionTestUtils.setField(actual,"lotMapper",liveLots);
        ReflectionTestUtils.setField(actual,"businessMapper",liveBusiness); ReflectionTestUtils.setField(actual,"returnDetailMapper",liveDetails); ReflectionTestUtils.setField(actual,"overMapper",over);
        when(livePolicy.hasSchema()).thenReturn(true); when(liveLots.sumRemain(7L,1L,10L)).thenReturn(2); when(liveBusiness.reserved(7L,1L,10L)).thenReturn(1);
        when(liveLots.listAvailable(7L,1L,10L)).thenReturn(List.of(lot(50L,1,"60"),lot(51L,1,"80")));
        when(liveDetails.heldLotReadOnly(50L)).thenReturn(1); when(liveDetails.heldLot(50L)).thenReturn(1);
        assertEquals(new BigDecimal("80"),actual.previewRefundLots(7L,1L,10L,1).getAmount());
        verify(liveDetails,never()).heldLot(anyLong()); verify(liveLots,never()).listAvailableForUpdate(anyLong(),anyLong(),anyLong()); verifyNoInteractions(over);
        assertEquals(new BigDecimal("80"),actual.consumeLots(7L,1L,10L,1,null,true).getAmount()); verify(liveDetails).heldLot(50L); verify(liveDetails).heldLot(51L);
        when(liveBusiness.reserved(7L,1L,10L)).thenReturn(2); assertThrows(BusinessException.class,()->actual.previewRefundLots(7L,1L,10L,1));
        verify(liveLots,never()).consume(anyLong(),anyInt()); verify(liveLots,never()).markExhausted(anyLong());
    }
}
