package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.BarrelLedgerService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"aquaflow.barrel.independent-rights-enabled=true","aquaflow.barrel.maintenance-enabled=false"})
class ReceivableBarrelActivationAuditIntegrationTest extends AbstractIntegrationTest {
    @Autowired BarrelLedgerService ledger;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void settlingCashOrderMustActivateTheBarrelDepositThatWasActuallyCollected(boolean pendingRecordExists) {
        long station=createStation("Audit station"), manager=createStaff("Audit manager","STATION_MANAGER",station,1);
        long customer=createCustomer("Audit customer","audit-receivable-customer");
        long product=createProduct("Audit barrel water",1,"20.00","30.00",1,"8.00");
        createInventoryFull(station,product,20,1,"8.00");
        jdbc.update("update inventory set deposit_price=50 where station_id=? and product_id=?",station,product);
        createInventoryRecord(station,product,20,"STOCK_IN",0);
        long address=createAddress(customer,"Audit address");
        jdbc.update("insert into customer_station_config(customer_id,station_id,offline_payment_enabled,due_days) values(?,?,1,30)",customer,station);
        String cus=customerToken(customer), mgr=staffToken(manager,"STATION_MANAGER",station);
        String items="[{\"productId\":"+product+",\"quantity\":1}]";
        var quote=post("/api/payments/quote",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":2,\"items\":"+items+"}");
        assertEquals(0,quote.code(),quote.toString()); assertEquals(70,quote.data().path("totalAmount").asInt());
        var created=post("/api/orders/create",cus,"{\"stationId\":"+station+",\"addressId\":"+address+",\"paymentMethod\":2,\"idempotencyKey\":\"audit-receivable-purchase\",\"items\":"+items+",\"barrelPurchases\":"+quote.data().path("barrelPurchases")+"}");
        assertEquals(0,created.code(),created.toString()); long order=created.data().path("orderId").asLong();
        assertEquals(0,ledger.rightQty(customer,station,product));
        if(pendingRecordExists) assertEquals(0,post("/api/payments",cus,"{\"orderId\":"+order+",\"paymentMethod\":2}").code());
        var settled=post("/api/manager/receivables/settle",mgr,"{\"customerId\":"+customer+",\"orderIds\":["+order+"]}");
        assertEquals(0,settled.code(),settled.toString());
        assertEquals(2,intOf("select payment_status from orders where id=?",order));
        assertEquals(70,intOf("select amount from payment_record where order_id=? and status=2",order));
        System.out.println("AUDIT_RECEIVABLE paid=2 paidAmount=70 purchase="+jdbc.queryForObject("select status from order_barrel_purchase where order_id=?",String.class,order)+" rights="+ledger.rightQty(customer,station,product)+" depositBalance="+decimalOf("select coalesce(sum(balance),0) from customer_deposit_account where customer_id=? and station_id=?",customer,station));
        assertEquals(1,ledger.rightQty(customer,station,product),"Collected combined-order barrel deposit must activate paid rights");
        assertEquals(0,ledger.occupiedQty(customer,station,product));
        assertEquals(50,decimalOf("select balance from customer_deposit_account where customer_id=? and station_id=?",customer,station).intValueExact());
        assertEquals("PAID",jdbc.queryForObject("select status from order_barrel_purchase where order_id=?",String.class,order));
        assertEquals(0,post("/api/manager/receivables/settle",mgr,"{\"customerId\":"+customer+",\"orderIds\":["+order+"]}").code());
        assertEquals(1,intOf("select count(*) from customer_barrel_lot where customer_id=?",customer));
        assertEquals(1,intOf("select count(*) from payment_record where order_id=? and status=2",order));
        assertEquals(0,post("/api/delivery/orders/"+order+"/accept",mgr,"{}").code());
        long item=jdbc.queryForObject("select id from order_item where order_id=?",Long.class,order);
        var delivered=post("/api/delivery/orders/"+order+"/complete",mgr,"{\"collected\":false,\"itemReturns\":[{\"orderItemId\":"+item+",\"expected\":0,\"actual\":0,\"reasons\":[]}]}");
        assertEquals(0,delivered.code(),delivered.toString());
        assertEquals(1,ledger.occupiedQty(customer,station,product));
    }
}
