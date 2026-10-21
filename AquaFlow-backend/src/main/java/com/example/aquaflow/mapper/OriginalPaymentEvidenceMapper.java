package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

@Mapper
public interface OriginalPaymentEvidenceMapper {
    @Select("select * from payment_original_success where payment_id=#{payment}") Map<String,Object> success(Long payment);
    @Insert("insert into payment_original_success(payment_id,customer_id,station_id,order_id,amount,payer_total_cents,binding_digest,transaction_digest,request_digest,success_epoch_second,success_nano) values(#{payment},#{customer},#{station},#{order},#{amount},#{payer},#{binding},#{transaction},#{digest},#{epoch},#{nano})") int insertSuccess(Map<String,Object> row);
    @Select("select count(*) from payment_original_refund_hold where payment_id=#{payment}") int holds(Long payment);
    @Select("select coalesce(sum(amount),0) from payment_original_refund_hold where payment_id=#{payment}") BigDecimal heldAmount(Long payment);
    @Select("select * from payment_original_refund_hold where payment_id=#{payment} and request_key=#{key}") Map<String,Object> hold(@Param("payment") Long payment,@Param("key") String key);
    @Insert("insert into payment_original_refund_hold(payment_id,request_key,binding_reference,binding_digest,original_transaction_reference,original_amount,amount,currency,request_digest) values(#{payment},#{key},#{reference},#{binding},#{transaction},#{original},#{amount},'CNY',#{digest})") int insertHold(Map<String,Object> row);
    @Select("select coalesce((select sum(water_amount+delivery_fee+floor_fee) from consumption_refund where original_payment_id=#{payment}),0)+coalesce((select sum(amount) from order_barrel_refund where original_payment_id=#{payment}),0)+coalesce((select sum(r.amount) from barrel_purchase_refund r join barrel_right_purchase p on p.id=r.purchase_id where p.payment_id=#{payment}),0)") BigDecimal refunded(Long payment);
}
