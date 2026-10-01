package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
@Mapper
public interface TicketExitReceiptMapper {
    @Select("select p.id paymentId,p.customer_id customerId,c.name customerName,pr.name productName,p.payment_method paymentMethod, sum(l.remain_qty) remainingQty, "
        + "case when sum(l.remain_qty)=p.ticket_qty then p.amount else round(least(sum(l.remain_qty*l.unit_price),p.amount),2) end refundAmount "
        + "from payment_record p join ticket_lot l on l.payment_record_id=p.id and l.source_type=1 and l.is_migrated=0 and l.status=1 and l.remain_qty>0 "
        + "left join customer c on c.id=p.customer_id left join product pr on pr.id=p.ticket_water_type_id "
        + "where p.station_id=#{station} and p.order_id is null and p.ticket_qty>0 and p.status=2 and not exists(select 1 from ticket_exit_refund e where e.original_payment_id=p.id) "
        + "group by p.id,p.customer_id,c.name,pr.name,p.payment_method,p.ticket_qty,p.amount order by p.id")
    List<Map<String,Object>> candidates(Long station);
    @Select("select count(*) from ticket_exit_refund where original_payment_id=#{id}") int exists(Long id);
    @Insert("insert into ticket_exit_refund(original_payment_id,refund_payment_id,quantity,amount,operator_id,create_time) values(#{original},#{refund},#{qty},#{amount},#{operator},now())")
    int insert(@Param("original") Long original,@Param("refund") Long refund,@Param("qty") int qty,@Param("amount") BigDecimal amount,@Param("operator") Long operator);
}
