package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

@Mapper
public interface ConsumptionRefundMapper {
    @Select("select * from orders where id=#{id} for update") Orders lockOrder(Long id);
    @Select("select * from payment_record where id=#{id} for update") PaymentRecord lockPayment(Long id);
    @Select("select coalesce(sum(water_amount),0) as waterAmount,coalesce(sum(delivery_fee),0) as deliveryFee,coalesce(sum(floor_fee),0) as floorFee from consumption_refund where original_payment_id=#{id}")
    Map<String,Object> refunded(Long id);
    @Insert("insert into consumption_refund(original_payment_id,refund_payment_id,order_id,scope,water_amount,delivery_fee,floor_fee,operator_id,note,create_time) values(#{original},#{refund},#{order},#{scope},#{water},#{delivery},#{floor},#{operator},#{note},now())")
    int record(@Param("original") Long original,@Param("refund") Long refund,@Param("order") Long order,@Param("scope") String scope,
               @Param("water") BigDecimal water,@Param("delivery") BigDecimal delivery,@Param("floor") BigDecimal floor,
               @Param("operator") Long operator,@Param("note") String note);
    @Select("select * from consumption_refund where order_id=#{order} order by refund_payment_id") List<Map<String,Object>> records(Long order);
}
