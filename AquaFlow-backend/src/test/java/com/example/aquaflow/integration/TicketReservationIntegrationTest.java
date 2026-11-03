package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.example.aquaflow.service.TicketLotService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.exception.BusinessException;
import java.math.BigDecimal;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class TicketReservationIntegrationTest extends AbstractIntegrationTest {
    @Autowired TicketLotService lots;
    @Autowired TicketAccountService tickets;
    long station,customer,product,address;
    void seed() {
        station=createStation("票预留站");customer=createCustomer("票预留客户","reservation-owner");
        product=createProduct("预留水",1,"20.00","30.00",1,"18.00");address=createAddress(customer,"预留地址");
        createTicketAccount(customer,station,product,10);
        jdbc.update("update ticket_lot set qty=4,remain_qty=4,unit_price=8 where customer_id=?",customer);
        insert("insert into ticket_lot(lot_no,customer_id,station_id,product_id,unit_price,qty,remain_qty,source_type,price_source,is_migrated,status) values('RESERVE-SECOND',?,?,?,12,6,6,3,3,1,1)",customer,station,product);
        lots.refreshRightAmount(customer,station,product);
    }
    long order(int quantity) {
        long id=createOrderFull(customer,address,station,product,1,1,3,"100.00","0.00","105.00",false,quantity);
        createOrderItemFull(id,product,"预留水",quantity,0,"18.00","0.00");return id;
    }
    int balance() {return intOf("select remain_quantity from ticket_account where customer_id=?",customer);}
    @Test
    void heldTicketsCannotBeSpentByAnotherOrderOrManualConsumption() {
        seed();long original=order(8);lots.reserveForOrder(customer,station,product,original,8);
        assertEquals(10,balance());assertEquals(2,lots.availableQuantity(customer,station,product));
        assertEquals(0,intOf("select count(*) from ticket_record where customer_id=?",customer));
        assertThrows(BusinessException.class,()->tickets.consumeTicket(customer,product,3,null,station,"manual-too-many"));
        tickets.consumeTicket(customer,product,2,order(2),station,null);
        assertEquals(8,balance());lots.consumeOrderReservation(customer,station,original);assertEquals(0,balance());
        assertEquals(0,decimalOf("select right_amount from ticket_account where customer_id=?",customer).signum());
    }
    @Test
    void paidReservationConsumesAndRefundsExactOriginalLotsOnlyOnce() {
        seed();long original=order(6);lots.reserveForOrder(customer,station,product,original,6);
        lots.consumeOrderReservation(customer,station,original);lots.consumeOrderReservation(customer,station,original);
        assertEquals(4,balance());assertEquals(1,intOf("select count(*) from ticket_record where order_id=? and source='消费'",original));
        lots.refundOrderReservation(customer,station,original);lots.refundOrderReservation(customer,station,original);
        assertEquals(10,balance());assertEquals(0,new BigDecimal("104.00").compareTo(decimalOf("select right_amount from ticket_account where customer_id=?",customer)));
        assertEquals(1,intOf("select count(*) from ticket_record where order_id=? and source='退款'",original));
    }
    @Test
    void releaseDoesNotConsumeOrRefundAndOriginalOrderCannotChangeReservation() {
        seed();long original=order(6);lots.reserveForOrder(customer,station,product,original,6);
        assertThrows(BusinessException.class,()->lots.reserveForOrder(customer,station,product,original,5));
        lots.releaseOrderReservation(customer,station,original);lots.releaseOrderReservation(customer,station,original);
        assertEquals(10,balance());assertEquals(10,lots.availableQuantity(customer,station,product));
        assertEquals(0,intOf("select count(*) from ticket_record where customer_id=?",customer));
        assertThrows(BusinessException.class,()->lots.consumeOrderReservation(customer,station,original));
    }
    @Test
    void concurrentReservationsCannotOverbookTheSameTicketBalance() throws Exception {
        seed();long first=order(7),second=order(7);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            java.util.function.LongFunction<Callable<Boolean>> task=id->()->{start.await();try{lots.reserveForOrder(customer,station,product,id,7);return true;}catch(BusinessException expected){return false;}};
            var a=pool.submit(task.apply(first));var b=pool.submit(task.apply(second));start.countDown();
            assertEquals(1,(a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0));
            assertEquals(3,lots.availableQuantity(customer,station,product));assertEquals(10,balance());
        } finally {pool.shutdownNow();}
    }
}
