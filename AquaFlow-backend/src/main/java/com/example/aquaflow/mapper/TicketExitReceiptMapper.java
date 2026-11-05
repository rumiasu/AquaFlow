package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
@Mapper
public interface TicketExitReceiptMapper {
    /**
     * 可退批次：同一原购票款可多次退出（部分退出后售后回补再退）。
     * 累计已退数量/金额达到原款上限即不再出现；退款金额按累计值封顶，最后一张补齐尾差。
     */
    @Select("select p.id paymentId,p.customer_id customerId,c.name customerName,pr.name productName,p.payment_method paymentMethod, "
        + "sum(l.remain_qty) remainingQty,coalesce(e.refunded_qty,0) refundedQty,coalesce(e.refunded_amount,0) refundedAmount, "
        + "case when coalesce(e.refunded_qty,0)+sum(l.remain_qty)>=p.ticket_qty "
        + "then greatest(0,p.amount-coalesce(e.refunded_amount,0)) "
        + "else least(round(sum(l.remain_qty*l.unit_price),2),greatest(0,p.amount-coalesce(e.refunded_amount,0))) end refundAmount "
        + "from payment_record p join ticket_lot l on l.payment_record_id=p.id and l.source_type=1 and l.is_migrated=0 and l.status=1 and l.remain_qty>0 "
        + "left join (select original_payment_id,sum(quantity) refunded_qty,sum(amount) refunded_amount from ticket_exit_refund group by original_payment_id) e on e.original_payment_id=p.id "
        + "left join customer c on c.id=p.customer_id left join product pr on pr.id=p.ticket_water_type_id "
        + "where p.station_id=#{station} and p.order_id is null and p.ticket_qty>0 and p.status=2 "
        + "and coalesce(e.refunded_qty,0)<p.ticket_qty and coalesce(e.refunded_amount,0)<p.amount "
        + "group by p.id,p.customer_id,c.name,pr.name,p.payment_method,p.ticket_qty,p.amount,e.refunded_qty,e.refunded_amount order by p.id")
    List<Map<String,Object>> candidates(Long station);
    /** 同一原款同一请求键只能产生一条退出凭据；重试在锁内返回原结果。 */
    @Select("select quantity,amount from ticket_exit_refund where original_payment_id=#{original} and idempotency_key=#{key} limit 1 for update")
    Map<String,Object> findByRequestKey(@Param("original") Long original,@Param("key") String key);
    @Select("select quantity,amount from ticket_exit_refund where original_payment_id=#{id} for update")
    List<Map<String,Object>> refundRowsForUpdate(Long id);
    @Insert("insert into ticket_exit_refund(original_payment_id,refund_payment_id,idempotency_key,quantity,amount,operator_id,create_time) values(#{original},#{refund},#{key},#{qty},#{amount},#{operator},now())")
    int insert(@Param("original") Long original,@Param("refund") Long refund,@Param("key") String key,@Param("qty") int qty,@Param("amount") BigDecimal amount,@Param("operator") Long operator);
}
