package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

/** C03范围和分次实退凭据；原案件及原款事实仍是授权/金额来源。 */
@Mapper
public interface ScopedRefusalRefundMapper {
    @Select("select id,customer_id as originalCustomerId,station_id as originalStationId,total_amount as debtAmount,payment_status as paymentStatus,status as orderStatus from orders where id=#{order} for update")
    Map<String,Object> lockOrder(Long order);
    @Select("select product_id from orders where id=#{order} and product_id>0")
    List<Long> legacyProduct(Long order);
    @Select("select o.product_id as productId,coalesce(p.name,concat('原单商品 #',o.product_id)) as productName from orders o left join product p on p.id=o.product_id where o.id=#{order} and o.product_id>0")
    List<Map<String,Object>> legacyProductInfo(Long order);
    @Select("select coalesce(sum(o.water_amount),0) as outstanding,coalesce(sum(case when o.due_date<curdate() then o.total_amount else 0 end),0) as overdueAmount,coalesce(sum(o.due_date<curdate()),0) as overdueCount,coalesce(max(case when o.due_date<curdate() then datediff(curdate(),o.due_date) else 0 end),0) as overdueDays from orders o where o.customer_id=#{customer} and o.station_id=#{station} and o.status<>5 and o.payment_status=1 and o.payment_method=2 and not exists(select 1 from customer_refusal_case r left join customer_refusal_resolution x on x.order_id=r.order_id where r.order_id=o.id and (coalesce(x.judgment_revoked,0)=0 or r.asset_freeze_confirmed=1 and coalesce(x.asset_freeze_released,0)=0))")
    Map<String,Object> refundCredit(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select r.*,o.customer_id as originalCustomerId,o.station_id as originalStationId,o.total_amount as debtAmount,o.product_id as legacyProductId from customer_refusal_case r left join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id where r.customer_id=#{customer} and r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and coalesce(x.asset_freeze_released,0)=0 and (o.id is null or o.payment_status=1 and o.status<>5) order by r.order_id")
    List<Map<String,Object>> active(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select product_id as productId,max(product_name_snapshot) as productName from order_item where order_id=#{order} group by product_id order by product_id")
    List<Map<String,Object>> products(Long order);
    @Select("select product_id as productId,amount from customer_refusal_freeze_scope where order_id=#{order} order by product_id")
    List<Map<String,Object>> scope(Long order);
    @Delete("delete from customer_refusal_freeze_scope where order_id=#{order}")
    int clearScope(Long order);
    @Insert("insert into customer_refusal_freeze_scope(order_id,product_id,amount) values(#{order},#{product},#{amount})")
    int insertScope(@Param("order") Long order,@Param("product") Long product,@Param("amount") BigDecimal amount);
    @Update("update customer_refusal_resolution set version=version+1 where order_id=#{order} and version=#{version} and asset_freeze_released=0")
    int advance(@Param("order") Long order,@Param("version") long version);
    @Insert("insert into customer_refusal_action(order_id,station_id,operator_id,actor_key,action,reason,idempotency_key,request_digest,from_version,to_version,scope_json,create_time) values(#{order},#{station},#{operator},#{actor},'SCOPE',#{reason},#{key},#{digest},#{version},#{version}+1,#{scopeJson},now())")
    int appendScope(Map<String,Object> row);
    @Select("select balance from customer_deposit_account where customer_id=#{customer} and station_id=#{station} for update")
    BigDecimal lockAccount(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select coalesce(max(balance),0) from customer_deposit_account where customer_id=#{customer} and station_id=#{station}")
    BigDecimal accountBalance(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select coalesce(sum(l.remain_qty*l.unit_price),0) from customer_barrel_lot l where l.customer_id=#{customer} and l.station_id=#{station} and l.product_id=#{product} and l.status=1")
    BigDecimal productMoney(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product);
    @Select("select coalesce(sum(p.amount),0) from barrel_return_payout p join barrel_record r on r.id=p.record_id join barrel_return_detail d on d.record_id=r.id where r.customer_id=#{customer} and r.station_id=#{station} and r.product_id=#{product} and d.status='RECEIVED'")
    BigDecimal pendingPaid(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product);
    @Select("select coalesce(sum(amount),0) from barrel_return_payout where record_id=#{record}")
    BigDecimal paid(Long record);
    @Select("select coalesce(sum(amount),0) from order_barrel_refund where return_record_id=#{record} and purchase_id=#{purchase} and payout_id is not null")
    BigDecimal combinedPaid(@Param("record") Long record,@Param("purchase") Long purchase);
    @Select("select coalesce(sum(amount),0) from barrel_purchase_refund where return_record_id=#{record} and purchase_id=#{purchase} and payout_id is not null")
    BigDecimal independentPaid(@Param("record") Long record,@Param("purchase") Long purchase);
    @Select("select * from barrel_return_payout where record_id=#{record} and request_key=#{key}")
    Map<String,Object> receipt(@Param("record") Long record,@Param("key") String key);
    @Select("select id,amount,payer_id as payerId,final_payout as finalPayout,create_time as createTime,request_key as requestKey from barrel_return_payout where record_id=#{record} order by id")
    List<Map<String,Object>> receipts(Long record);
    @Insert("insert into barrel_return_payout(record_id,actor_key,request_key,request_digest,amount,payer_id,final_payout,recorded_epoch_second,recorded_nano) values(#{record},#{actor},#{key},#{digest},#{amount},#{payer},#{last},#{epoch},#{nano})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertPayout(Map<String,Object> row);
    @Insert("insert into barrel_purchase_refund(payment_id,purchase_id,return_record_id,amount,payout_id) values(#{payment},#{purchase},#{record},#{amount},#{payout})")
    int independentProof(@Param("payment") Long payment,@Param("purchase") Long purchase,@Param("record") Long record,@Param("amount") BigDecimal amount,@Param("payout") Long payout);
}
