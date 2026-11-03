package com.example.aquaflow.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Reservation reads are current reads under the customer's product account lock. */
@Mapper
public interface TicketHoldMapper {
    @Select("select h.* from order_ticket_hold h join ticket_lot l on l.id=h.lot_id where l.customer_id=#{customer} and l.station_id=#{station} and l.product_id=#{product} and h.state='HELD' order by h.id for update")
    List<Map<String,Object>> heldForAccount(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product);
    @Select("select * from order_ticket_hold where order_id=#{order} and product_id=#{product} order by lot_id for update")
    List<Map<String,Object>> forOrderProduct(@Param("order") Long order,@Param("product") Long product);
    @Select("select distinct product_id from order_ticket_hold where order_id=#{order} order by product_id")
    List<Long> products(@Param("order") Long order);
    @Insert("insert into order_ticket_hold(order_id,lot_id,product_id,quantity,unit_price) values(#{order},#{lot},#{product},#{quantity},#{price})")
    int insert(@Param("order") Long order,@Param("lot") Long lot,@Param("product") Long product,@Param("quantity") int quantity,@Param("price") BigDecimal price);
    @Update("update order_ticket_hold set state=#{to}, update_time=NOW() where id=#{id} and state=#{from}")
    int move(@Param("id") Long id,@Param("from") String from,@Param("to") String to);
    @Select("select coalesce(sum(h.quantity),0) from order_ticket_hold h join ticket_lot l on l.id=h.lot_id where l.customer_id=#{customer} and l.station_id=#{station} and l.product_id=#{product} and h.state='HELD'")
    int heldQuantity(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product);
}
