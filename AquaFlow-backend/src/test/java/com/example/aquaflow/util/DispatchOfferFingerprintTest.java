package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("外派报价确认摘要稳定性与完整性")
class DispatchOfferFingerprintTest {
    static Map<String,Object> offer() {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("order_id",11L);row.put("source_station_id",1L);row.put("target_station_id",null);row.put("offer_version",1L);
        row.put("service_amount",new BigDecimal("40.00"));row.put("net_barrels",2);row.put("barrel_mode","RETURN_EMPTY");
        row.put("barrel_amount",BigDecimal.ZERO);row.put("barrel_items","商品 1：1 个同型空桶；商品 2：1 个同型空桶");row.put("note","合成报价");return row;
    }
    @Test void allFinancialAndIdentityDimensionsAreBound() {
        var original=offer();String token=DispatchOfferFingerprint.token(original,2L);
        Map<String,Object> changes=Map.of("order_id",12L,"source_station_id",3L,"target_station_id",2L,"offer_version",2L,
                "service_amount",new BigDecimal("5.00"),"net_barrels",3,"barrel_mode","SETTLE_BARREL","barrel_amount",new BigDecimal("30"),"barrel_items","商品 1：2 个同型空桶","note","另一约定");
        for(var change:changes.entrySet()) {var row=new LinkedHashMap<>(original);row.put(change.getKey(),change.getValue());assertNotEquals(token,DispatchOfferFingerprint.token(row,2L),change.getKey());}
        assertNotEquals(token,DispatchOfferFingerprint.token(original,3L));
    }
    @Test void numericScaleAndItemDisplayOrderAreStable() {
        var a=offer();var b=offer();b.put("service_amount",new BigDecimal("40.0000"));b.put("barrel_amount",new BigDecimal("0.00"));
        b.put("barrel_items","商品 2：1 个同型空桶；商品 1：1 个同型空桶");
        assertEquals(DispatchOfferFingerprint.token(a,2L),DispatchOfferFingerprint.token(b,2L));
    }
    @Test void nullDoesNotBecomeZeroOrEmpty() {
        var a=offer();a.put("barrel_amount",null);var b=offer();assertNotEquals(DispatchOfferFingerprint.token(a,2L),DispatchOfferFingerprint.token(b,2L));
        a=offer();a.put("note",null);b.put("note","");assertNotEquals(DispatchOfferFingerprint.token(a,2L),DispatchOfferFingerprint.token(b,2L));
    }
    @Test void missingOrInvalidVersionCannotIssueConsent() {
        for(Object version:Arrays.asList(null,0L,-1L)) {var row=offer();row.put("offer_version",version);assertNull(DispatchOfferFingerprint.token(row,2L));}
        assertNull(DispatchOfferFingerprint.token(offer(),null));
    }
    @Test void malformedOrWrongTokensNeverMatch() {
        String token=DispatchOfferFingerprint.token(offer(),2L);assertTrue(DispatchOfferFingerprint.matches(token,token));
        for(String candidate:Arrays.asList(null,"",token.toUpperCase(Locale.ROOT),token+"0","a".repeat(64)))assertFalse(DispatchOfferFingerprint.matches(candidate,token));
        assertFalse(DispatchOfferFingerprint.matches(token,null));
    }
    @Test void sameTermsInADifferentRoundStillInvalidateTheOldToken() {
        var a=offer();var b=offer();b.put("offer_version",3L);assertNotEquals(DispatchOfferFingerprint.token(a,2L),DispatchOfferFingerprint.token(b,2L));
    }
}
