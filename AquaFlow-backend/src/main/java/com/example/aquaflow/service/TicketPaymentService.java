package com.example.aquaflow.service;

import com.example.aquaflow.constant.*;
import com.example.aquaflow.entity.*;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.util.StationUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Server-owned split: tickets settle water; one real cash record settles only the remainder. */
@Service
public class TicketPaymentService {
    private final JdbcTemplate jdbc;
    private final TicketLotService lots;
    private final TicketDeliveryBenefitService benefits;
    private final OrderItemMapper items;
    public TicketPaymentService(JdbcTemplate jdbc,TicketLotService lots,TicketDeliveryBenefitService benefits,OrderItemMapper items) {
        this.jdbc=jdbc;this.lots=lots;this.benefits=benefits;this.items=items;
    }
    public record Split(Long orderId,BigDecimal water,BigDecimal delivery,BigDecimal floor,BigDecimal credit,
                        BigDecimal deposit,BigDecimal cashDue,Integer cashMethod,Long voucher,String state) {}
    private static BigDecimal d(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    private static Long id(Object value){return value==null?null:((Number)value).longValue();}
    public Split get(Long order) { return read(order,false); }
    public Split getForUpdate(Long order) { return read(order,true); }
    private Split read(Long order,boolean current) {
        if(order==null)return null;
        var rows=jdbc.queryForList("select * from order_ticket_payment where order_id=?"+(current?" for update":""),order);
        if(rows.isEmpty())return null;var r=rows.get(0);
        return new Split(order,d(r.get("water_amount")),d(r.get("delivery_fee")),d(r.get("floor_fee")),d(r.get("delivery_credit")),d(r.get("deposit_amount")),d(r.get("cash_due")),r.get("cash_method")==null?null:((Number)r.get("cash_method")).intValue(),id(r.get("voucher_payment_id")),String.valueOf(r.get("state")));
    }
    /** Quote uses the very same unreserved FIFO source as preparation; no client coupon/amount accepted. */
    public BigDecimal previewCredit(Long customer,Long station,Map<Long,Integer> quantities,BigDecimal delivery) {
        if(delivery.signum()<=0)return BigDecimal.ZERO;
        Set<Long> sources=new HashSet<>();
        for(var e:new TreeMap<>(quantities).entrySet()) {
            if(lots.availableQuantity(customer,station,e.getKey())<e.getValue())return BigDecimal.ZERO;
            sources.addAll(lots.previewSourcePayments(customer,station,e.getKey(),e.getValue()));
        }
        return benefits.preview(customer,station,sources)==null?BigDecimal.ZERO:delivery;
    }
    @Transactional public Split prepare(Orders order,Integer cashMethod,BigDecimal expectedCash,BigDecimal expectedCredit) {
        Split prior=getForUpdate(order.getId());if(prior!=null)return prior;
        if(!Objects.equals(order.getPaymentMethod(),PayMethod.TICKET))throw new BusinessException("仅水票订单可预留水票");
        var lines=items.listByOrderId(order.getId());if(lines.isEmpty())throw new BusinessException("订单缺少水票商品");
        Map<Long,Integer> quantities=new TreeMap<>();for(var i:lines)quantities.merge(i.getProductId(),i.getQuantity(),Integer::sum);
        for(var e:quantities.entrySet())lots.reserveForOrder(order.getCustomerId(),order.getStationId(),e.getKey(),order.getId(),e.getValue());
        BigDecimal delivery=d(order.getDeliveryFee()),floor=d(order.getFloorFee()),deposit=d(order.getDepositAmount());
        Long voucher=delivery.signum()>0?benefits.reserve(order.getCustomerId(),order.getStationId(),order.getId()):null;
        BigDecimal credit=voucher==null?BigDecimal.ZERO:delivery;
        BigDecimal cash=delivery.subtract(credit).add(floor).add(deposit);
        if(cash.signum()>0 && !Objects.equals(cashMethod,PayMethod.CASH) && !Objects.equals(cashMethod,PayMethod.WECHAT))
            throw new BusinessException("水票仅抵水费，请选择配送、楼层等费用的付款方式");
        if(expectedCash!=null && expectedCash.compareTo(cash)!=0 || expectedCredit!=null && expectedCredit.compareTo(credit)!=0)
            throw new BusinessException("服务费或配送优惠已变化，请重新确认报价；本次未下单扣票");
        jdbc.update("insert into order_ticket_payment(order_id,customer_id,station_id,water_amount,delivery_fee,floor_fee,delivery_credit,deposit_amount,cash_due,cash_method,voucher_payment_id) values(?,?,?,?,?,?,?,?,?,?,?)",order.getId(),order.getCustomerId(),order.getStationId(),order.getWaterAmount(),delivery,floor,credit,deposit,cash,cash.signum()>0?cashMethod:null,voucher);
        jdbc.update("update orders set total_amount=water_amount+?,payment_status=?,update_time=NOW() where id=? and payment_status in (0,1)",cash,cash.signum()>0 && Objects.equals(cashMethod,PayMethod.CASH)?PaymentStatus.PENDING:order.getPaymentStatus(),order.getId());
        return get(order.getId());
    }
    public boolean cod(Orders order) {
        Split s=get(order.getId());return Objects.equals(order.getPaymentMethod(),PayMethod.CASH) || s!=null && Objects.equals(s.cashMethod(),PayMethod.CASH) && !"CANCELLED".equals(s.state());
    }
    public void validateRecord(Orders order,PaymentRecord record) {
        Split s=get(order.getId());
        int method=s==null?order.getPaymentMethod():s.cashDue().signum()>0?s.cashMethod():PayMethod.TICKET;
        BigDecimal amount=s==null?order.getTotalAmount():s.cashDue();
        if(!Objects.equals(record.getPaymentMethod(),method) || record.getAmount()==null || amount.compareTo(record.getAmount())!=0)
            throw new BusinessException("原款方式或金额与服务端订单分项不符，请核对原请求");
    }
    @Transactional public void paid(Orders order) {
        Split s=getForUpdate(order.getId());if(s==null)return;
        if("CANCELLED".equals(s.state()))throw new BusinessException("原水票支付已终止，不能再次扣票");
        if("HELD".equals(s.state())) {
            lots.consumeOrderReservation(order.getCustomerId(),order.getStationId(),order.getId());
            if(jdbc.update("update order_ticket_payment set state='CONSUMED',paid_time=NOW() where order_id=? and state='HELD'",order.getId())!=1)throw new BusinessException("水票预留状态已变化");
        }
    }
    @Transactional public void delivered(Orders order) {
        if(get(order.getId())==null)return;
        paid(order);benefits.delivered(order.getId());
    }
    @Transactional public void cancel(Orders order) {
        Split s=getForUpdate(order.getId());if(s==null || Set.of("CANCELLED","REFUNDED").contains(s.state()))return;
        if("HELD".equals(s.state()))lots.releaseOrderReservation(order.getCustomerId(),order.getStationId(),order.getId());
        else lots.refundOrderReservation(order.getCustomerId(),order.getStationId(),order.getId());
        benefits.releaseBeforeDelivery(order.getId());
        if(jdbc.update("update order_ticket_payment set state='CANCELLED',cancel_time=NOW() where order_id=? and state in ('HELD','CONSUMED')",order.getId())!=1)throw new BusinessException("原水票取消状态已变化，本次回滚");
    }
    @Transactional public void refundWater(Orders order) {
        Split s=getForUpdate(order.getId());if(s==null || !"CONSUMED".equals(s.state()))throw new BusinessException("本单水票已回补或尚未消费，不能重复退款");
        lots.refundOrderReservation(order.getCustomerId(),order.getStationId(),order.getId());
        if(jdbc.update("update order_ticket_payment set state='REFUNDED' where order_id=? and state='CONSUMED'",order.getId())!=1)throw new BusinessException("原水票退款状态已变化，本次回滚");
        jdbc.update("insert into audit_log(module,action,target,detail,create_time) values('PAYMENT','TICKET_REFUND',?, '按原预留批次回补水票，不折现金；已用配送优惠不返还',NOW())","order:"+order.getId());
    }
    public int refundableQty(Long order) {
        return jdbc.queryForList("select quantity from order_ticket_hold where order_id=? and state='CONSUMED' order by id for update",Integer.class,order).stream().mapToInt(Integer::intValue).sum();
    }
    public void project(Orders order) {
        if(order==null)return;Split s=getForUpdate(order.getId());if(s==null)return;
        order.setTicketFeePaymentMethod(s.cashMethod());order.setTicketCashAmount(s.cashDue());order.setTicketDeliveryCredit(s.credit());order.setTicketWaterState(s.state());
    }
}
