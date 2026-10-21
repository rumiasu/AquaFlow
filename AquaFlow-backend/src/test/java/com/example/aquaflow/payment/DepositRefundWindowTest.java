package com.example.aquaflow.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class DepositRefundWindowTest {
    final Instant paid=Instant.parse("2024-02-29T08:15:30Z"),deadline=paid.plus(Duration.ofDays(365));
    DepositRefundWindow.Facts facts(Instant time,boolean verified,boolean actual,boolean unknown,String refunded) {
        return new DepositRefundWindow.Facts(time,new BigDecimal("50"),new BigDecimal(refunded),verified,actual,unknown);
    }
    @ParameterizedTest @ValueSource(longs={-1,0}) void beforeAndExactBoundaryRetainOriginalChannel(long seconds) {
        var result=DepositRefundWindow.decide(facts(paid,true,true,false,"0"),deadline.plusSeconds(seconds));
        assertEquals(DepositRefundWindow.Route.ORIGINAL_CHANNEL,result.route());assertEquals(new BigDecimal("50.00"),result.remaining());
    }
    @Test void onlyStrictlyPast365DaysUsesVerifiedOfflineHandoverAndRemainingOriginalMoney() {
        var result=DepositRefundWindow.decide(facts(paid,true,true,false,"38"),deadline.plusNanos(1));
        assertEquals(DepositRefundWindow.Route.OFFLINE_HANDOVER,result.route());assertEquals(new BigDecimal("12.00"),result.remaining());assertEquals(deadline,result.deadline());
    }
    @Test void missingFutureOrUnverifiedSourceNeverBecomesExpiredCash() {
        for(var source:new DepositRefundWindow.Facts[]{facts(null,true,true,false,"0"),facts(deadline.plusSeconds(2),true,true,false,"0"),facts(paid,false,true,false,"0"),facts(paid,true,false,false,"0")})
            assertEquals(DepositRefundWindow.Route.PENDING,DepositRefundWindow.decide(source,deadline.plusSeconds(1)).route());
    }
    @Test void unresolvedOriginalRefundBlocksCashEvenAfterExpiryAndPreservesRemainingLiability() {
        var result=DepositRefundWindow.decide(facts(paid,true,true,true,"12"),deadline.plus(Duration.ofDays(40)));
        assertEquals(DepositRefundWindow.Route.PENDING,result.route());assertEquals(new BigDecimal("38.00"),result.remaining());
    }
    @ParameterizedTest @ValueSource(strings={"-1","50.01","0.001"}) void invalidRefundedMoneyNeverCreatesACashBudget(String refunded) {
        assertEquals(DepositRefundWindow.Route.PENDING,DepositRefundWindow.decide(facts(paid,true,true,false,refunded),deadline.plusSeconds(1)).route());
    }
    @Test void noRemainingOriginalMoneyNeverAuthorizesAnotherPayout() {
        var result=DepositRefundWindow.decide(facts(paid,true,true,false,"50"),deadline.plusSeconds(1));assertEquals(DepositRefundWindow.Route.PENDING,result.route());assertEquals(0,result.remaining().signum());
    }
    @Test void offsetsAndLeapYearsUseOriginalInstantInsteadOfClaimOrBucketReuseDate() {
        Instant same=OffsetDateTime.parse("2024-02-29T16:15:30+08:00").toInstant();assertEquals(paid,same);
        var result=DepositRefundWindow.decide(facts(same,true,true,false,"0"),Instant.parse("2025-02-28T08:15:30.000000001Z"));assertEquals(DepositRefundWindow.Route.OFFLINE_HANDOVER,result.route());
    }
}
