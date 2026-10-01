package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface BusinessWaitingMapper {
    @Select("select distinct o.id from orders o join barrel_right_reservation r on r.owner_type='ORDER' and r.owner_id=o.id where o.status=1 and o.payment_status=0 and o.payment_method<>2 and o.create_time<date_sub(now(),interval #{minutes} minute) and not exists(select 1 from payment_record p where p.order_id=o.id and p.status in (1,2)) order by o.id limit 100")
    List<Long> unpaidExpired(int minutes);
    @Select("select count(*) from payment_record where order_id=#{order} and status in (1,2)") int hasActivePayment(Long order);
    @Select("select distinct o.id as orderId,o.order_no as orderNo,o.create_time as createTime,sum(greatest(0,r.need_qty-r.reserved_qty)) as shortageQty from orders o join inventory_reservation r on r.order_id=o.id and r.status=1 where coalesce(o.delivery_station_id,o.station_id)=#{station} and o.status in (1,2) and (o.payment_status=2 or o.payment_method=2) and r.need_qty>r.reserved_qty group by o.id,o.order_no,o.create_time order by o.create_time")
    List<Map<String,Object>> waitingStock(Long station);
    @Select("select r.id as recordId,r.customer_id as customerId,d.status,d.refund_due_time as refundDueTime,d.approved_time as approvedTime,r.deposit_refund as refundAmount from barrel_return_detail d join barrel_record r on r.id=d.record_id where r.station_id=#{station} and ((d.status='APPLIED' and r.create_time<date_sub(now(),interval 2 hour)) or (d.status='RECEIVED' and d.refund_due_time<now())) order by r.id")
    List<Map<String,Object>> delayedReturns(Long station);
}
