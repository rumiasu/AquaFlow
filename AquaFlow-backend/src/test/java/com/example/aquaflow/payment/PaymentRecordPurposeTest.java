package com.example.aquaflow.payment;

import com.example.aquaflow.entity.PaymentRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 本人账单用途的纯展示测试，不连接数据库或修改资金状态。 */
class PaymentRecordPurposeTest {
    @Test
    void unknownPurposeDoesNotFollowNotesOrAmounts() {
        PaymentRecord payment = new PaymentRecord();
        payment.setNote("在线购票、独立押金");
        payment.setAmount(new BigDecimal("60.00"));
        payment.setBarrelDeposit(new BigDecimal("60.00"));
        assertEquals("支付记录", payment.getPurposeText());
    }

    @Test
    void independentDepositRequiresTheReadOnlyOriginalPaymentProjection() {
        PaymentRecord payment = new PaymentRecord();
        assertEquals("支付记录", payment.getPurposeText());
        payment.setIndependentBarrelPurchase(true);
        for (int status : new int[]{1, 2, 3, 4}) {
            payment.setStatus(status);
            assertEquals("独立押金", payment.getPurposeText(), "资金状态不改变原用途");
        }
    }

    @Test
    void ticketPurchaseRequiresBothProductAndPositiveQuantity() {
        PaymentRecord payment = new PaymentRecord();
        payment.setTicketWaterTypeId(9L);
        assertEquals("支付记录", payment.getPurposeText());
        for (int quantity : new int[]{0, -1}) {
            payment.setTicketQty(quantity);
            assertEquals("支付记录", payment.getPurposeText());
        }
        payment.setTicketQty(10);
        assertEquals("水票购买", payment.getPurposeText());
        payment.setTicketWaterTypeId(null);
        assertEquals("支付记录", payment.getPurposeText());
    }

    @Test
    void orderPaymentKeepsItsOrderPurposeForRefundAndSupplementalDeposit() {
        PaymentRecord payment = new PaymentRecord();
        payment.setOrderId(5L);
        payment.setBarrelDeposit(new BigDecimal("60.00"));
        payment.setTicketWaterTypeId(9L);
        payment.setTicketQty(10);
        payment.setIndependentBarrelPurchase(true);
        for (int status : new int[]{1, 2, 3, 4}) {
            payment.setStatus(status);
            assertEquals("订单支付", payment.getPurposeText());
        }
    }
}
