package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** 拒付证据与资产冻结授权；欠款是否有效仍以订单支付状态为准。 */
@Mapper
public interface ConfirmedRefusalMapper {
    @Insert("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(#{order},#{exception},#{customer},#{asset},#{debt},#{freeze},#{operator},#{note},now())")
    int insert(@Param("order") Long order,@Param("exception") Long exception,@Param("customer") Long customer,
               @Param("asset") Long asset,@Param("debt") Long debt,@Param("freeze") boolean freeze,@Param("operator") Long operator,@Param("note") String note);
    @Select("select r.* from customer_refusal_case r join orders o on o.id=r.order_id where r.customer_id=#{customer} and o.payment_status=1 and o.status<>5 and (r.debt_station_id=#{station} or r.asset_station_id=#{station}) order by r.order_id for update")
    List<Map<String,Object>> activeForUpdate(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select count(*) from customer_refusal_case r join orders o on o.id=r.order_id where r.customer_id=#{customer} and r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and o.payment_status=1 and o.status<>5")
    int frozen(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select distinct r.customer_id as customerId from customer_refusal_case r join orders o on o.id=r.order_id where r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and o.payment_status=1 and o.status<>5")
    List<Map<String,Object>> frozenCustomers(@Param("station") Long station);
    @Select("select r.*,o.total_amount as debtAmount,o.payment_status as paymentStatus from customer_refusal_case r join orders o on o.id=r.order_id where r.asset_station_id=#{station} or r.debt_station_id=#{station} order by r.create_time desc limit 200")
    List<Map<String,Object>> list(@Param("station") Long station);
    @Update("update customer_refusal_case set asset_freeze_confirmed=1,asset_confirmed_by=#{operator},asset_confirmed_time=now() where order_id=#{order} and asset_station_id=#{station} and asset_freeze_confirmed=0")
    int confirmFreeze(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator);
}
