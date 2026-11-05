package com.example.aquaflow.service;

import com.example.aquaflow.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BarrelExceptionRecoveryServiceTest {
    private OrderBarrelException exception(int qty) {
        var e=new OrderBarrelException();e.setId(1L);e.setCustomerId(10L);e.setStationId(20L);e.setOrderId(30L);
        e.setStatus("STAFF_RECORDED");e.setCategory("RETURN_SHORT");e.setType("SHORT_RETURN");e.setDiscrepancy(qty);e.setDeliveryQty(qty);e.setReturnQty(0);return e;
    }
    private BarrelRecord row(long id,long product,int type,int before,int after) {
        var r=new BarrelRecord();r.setId(id);r.setCustomerId(10L);r.setStationId(20L);r.setProductId(product);
        r.setType(type);r.setStatus(3);r.setOverBefore(before);r.setOverAfter(after);r.setRelatedOrderId(30L);
        r.setDepositRefund(java.math.BigDecimal.ZERO);r.setQuantity(Math.abs(after-before));r.setDeliveredQty(Math.max(0,after-before));r.setReturnedQty(0);return r;
    }
    private BarrelExceptionRecoveryService.Evidence proof(OrderBarrelException e,List<BarrelRecord> sources,List<BarrelRecord> history) {
        return BarrelExceptionRecoveryService.prove(e,sources,r->true,r->history.stream().filter(h->h.getProductId().equals(r.getProductId())).toList());
    }
    @Test void exactReturnClosesWithoutChangingOriginalDifference() {
        var e=exception(2);var p=proof(e,List.of(row(1,1,8,0,2)),List.of(row(2,1,7,2,0)));
        assertTrue(p.returned());assertEquals(List.of(2L),p.receipts());assertEquals(2,e.getDiscrepancy());
    }
    @Test void partialReturnDoesNotAllocateToAnOrder() {
        var p=proof(exception(1),List.of(row(1,1,8,2,3)),List.of(row(2,1,7,3,1)));
        assertTrue(p.supported());assertFalse(p.returned());
    }
    @Test void everyProductMustBeReturned() {
        var e=exception(3);var sources=List.of(row(1,1,8,0,1),row(2,2,8,0,2));
        assertFalse(proof(e,sources,List.of(row(3,1,7,1,0))).returned());
        assertTrue(proof(e,sources,List.of(row(3,1,7,1,0),row(4,2,7,2,-1))).returned());
    }
    @Test void historicClearRemainsValidAfterNewDebt() {
        var history=List.of(row(2,1,7,2,0),row(3,1,8,0,1));
        assertTrue(proof(exception(2),List.of(row(1,1,8,0,2)),history).returned());
        assertFalse(proof(exception(1),List.of(row(3,1,8,0,1)),List.of()).returned());
    }
    @Test void newDeliveryAddsToDebtUntilActuallyReturned() {
        assertFalse(proof(exception(2),List.of(row(1,1,8,0,2)),List.of(row(2,1,8,2,4),row(3,1,7,4,2))).returned());
        assertTrue(proof(exception(2),List.of(row(1,1,8,0,2)),List.of(row(2,1,8,2,4),row(3,1,7,4,0))).returned());
    }
    @Test void incompleteProvenanceAndAmbiguousSourceAreNeverProof() {
        var e=exception(2);var original=row(1,1,8,0,2);var clear=row(2,1,7,2,0);
        assertFalse(BarrelExceptionRecoveryService.prove(e,List.of(original),r->false,r->List.of(clear)).supported());
        assertFalse(proof(e,List.of(original,original),List.of(clear)).supported());
        assertFalse(proof(e,List.of(row(1,1,8,-1,1)),List.of(clear)).supported());
        assertFalse(proof(e,List.of(row(1,1,8,0,3),row(2,2,8,2,1)),List.of(clear)).supported());
        assertFalse(proof(exception(3),List.of(original),List.of(clear)).supported());
    }
    @Test void otherStationCustomerOrProductCannotClose() {
        for(int field=0;field<3;field++) {
            var clear=row(2,1,7,2,0);
            if(field==0)clear.setStationId(99L);if(field==1)clear.setCustomerId(99L);if(field==2)clear.setProductId(99L);
            assertFalse(proof(exception(2),List.of(row(1,1,8,0,2)),List.of(clear)).returned());
        }
    }
    @Test void gapsAdjustmentsOtherOrderReturnsAndMalformedReceiptsBlock() {
        var source=row(1,1,8,0,2);
        assertFalse(proof(exception(2),List.of(source),List.of(row(2,1,7,1,0))).returned());
        assertFalse(proof(exception(2),List.of(source),List.of(row(2,1,6,2,1),row(3,1,7,1,0))).returned());
        assertFalse(proof(exception(2),List.of(source),List.of(row(2,1,8,2,1),row(3,1,7,1,0))).returned());
        var invalid=row(2,1,7,2,0);invalid.setQuantity(1);
        assertFalse(proof(exception(2),List.of(source),List.of(invalid)).returned());
        invalid.setQuantity(2);invalid.setOverBefore(null);
        assertFalse(proof(exception(2),List.of(source),List.of(invalid)).returned());
    }
    @Test void missingHandlingSchemaDegradesWithoutQueryingAbsentHistory() {
        var exceptions=org.mockito.Mockito.mock(com.example.aquaflow.mapper.OrderBarrelExceptionMapper.class);
        var orders=org.mockito.Mockito.mock(com.example.aquaflow.mapper.OrderMapper.class);
        var records=org.mockito.Mockito.mock(com.example.aquaflow.mapper.BarrelRecordMapper.class);
        var actions=org.mockito.Mockito.mock(com.example.aquaflow.mapper.CaseHandlingMapper.class);
        var overs=org.mockito.Mockito.mock(com.example.aquaflow.mapper.CustomerBarrelOverMapper.class);
        var service=new BarrelExceptionRecoveryService(exceptions,orders,records,actions,overs);
        assertFalse(service.evidence(exception(1)).supported());
        org.mockito.Mockito.verify(actions).schemaReady();
        org.mockito.Mockito.verifyNoMoreInteractions(actions);
        org.mockito.Mockito.verifyNoInteractions(exceptions,orders,records,overs);
    }
    @Test void contradictoryAmountsAndMalformedPhysicalReceiptCannotClose() {
        var e=exception(2);e.setDeliveryQty(3);
        assertFalse(proof(e,List.of(row(1,1,8,0,2)),List.of(row(2,1,7,2,0))).supported());
        var source=row(1,1,8,0,2);source.setDeliveredQty(3);source.setQuantity(3);
        assertFalse(proof(exception(2),List.of(source),List.of(row(2,1,7,2,0))).supported());
        var clear=row(2,1,7,2,0);clear.setDepositRefund(java.math.BigDecimal.ONE);
        assertFalse(proof(exception(2),List.of(row(1,1,8,0,2)),List.of(clear)).returned());
    }
    @Test void otherLiabilitiesDecisionsAndCategoriesRemainOpen() {
        var sources=List.of(row(1,1,8,0,2));var history=List.of(row(2,1,7,2,0));
        var e=exception(2);e.setWaterOwed(1);assertFalse(proof(e,sources,history).supported());
        e=exception(2);e.setSuggestedTicketQty(1);assertFalse(proof(e,sources,history).supported());
        e=exception(2);e.setRefundCashAmount(java.math.BigDecimal.ONE);assertFalse(proof(e,sources,history).supported());
        e=exception(2);e.setManagerAction("IGNORE");assertFalse(proof(e,sources,history).supported());
        e=exception(2);e.setCategory("RETURN_DAMAGE");assertFalse(proof(e,sources,history).supported());
        e=exception(2);e.setStatus("MANAGER_APPROVED");assertFalse(proof(e,sources,history).supported());
    }
}
