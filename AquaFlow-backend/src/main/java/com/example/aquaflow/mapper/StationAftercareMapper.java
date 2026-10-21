package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface StationAftercareMapper {
    String SOURCE="select c.*,p.amount as actualAmount,p.water_amount as actualWater,p.delivery_fee as actualDelivery,p.floor_fee as actualFloor,p.status as actualStatus,p.station_id as refundStationId,p.customer_id as actualCustomerId,p.order_id as actualOrderId,p.payment_method as actualMethod,p.barrel_deposit as actualDeposit,o.amount as originalAmount,o.barrel_deposit as originalDeposit,o.status as originalStatus,o.customer_id as originalCustomerId,o.order_id as originalOrderId,o.payment_method as originalMethod from consumption_refund c left join payment_record p on p.id=c.refund_payment_id left join payment_record o on o.id=c.original_payment_id ";
    @Select(SOURCE+"where c.refund_payment_id=#{payment}") Map<String,Object> source(Long payment);
    @Select(SOURCE+"where c.order_id=#{order} order by c.refund_payment_id") List<Map<String,Object>> sources(Long order);
    @Select("select count(*) from station_aftercare where order_id=#{order}") int count(Long order);
    @Select("select refund_payment_id from station_aftercare where order_id=#{order} order by refund_payment_id for update") List<Long> currentCases(Long order);
    @Select("select count(*) from inter_station_recovery where order_id=#{order}") int legacyRecovery(Long order);
    @Select("select distinct o.id from orders o join inter_station_settlement s on s.order_id=o.id and s.settled_time is not null join consumption_refund c on c.order_id=o.id left join station_aftercare a on a.refund_payment_id=c.refund_payment_id where (o.station_id=#{station} or coalesce(o.settle_station_id,o.delivery_station_id,o.station_id)=#{station}) and o.station_id<>coalesce(o.settle_station_id,o.delivery_station_id,o.station_id) and not exists(select 1 from inter_station_recovery r where r.order_id=o.id) and (a.refund_payment_id is null or a.status='PROPOSED' or a.status='AGREED' and a.proposed_amount>0) and (exists(select 1 from station_aftercare x where x.order_id=o.id) or (select coalesce(sum(r.water_amount+r.delivery_fee+r.floor_fee),0) from consumption_refund r where r.order_id=o.id)<(select coalesce(sum(p.amount-coalesce(p.barrel_deposit,0)),0) from payment_record p where p.order_id=o.id and p.amount>0 and p.status in (2,3))) order by o.id desc limit 201")
    List<Long> pendingOrders(Long station);
    @Select("select coalesce(sum(amount-coalesce(barrel_deposit,0)),0) from payment_record where order_id=#{order} and amount>0 and status in (2,3)") java.math.BigDecimal originalConsumption(Long order);
    @Select("select * from station_aftercare where refund_payment_id=#{payment} for update") Map<String,Object> lock(Long payment);
    @Select("select * from station_aftercare where refund_payment_id=#{payment}") Map<String,Object> get(Long payment);
    @Insert("insert into station_aftercare(refund_payment_id,order_id,refund_station_id,other_station_id,refund_amount,proposed_amount,proposed_station_id,proposed_by,reason,status,version) values(#{payment},#{order},#{refundStation},#{otherStation},#{refundAmount},#{amount},#{station},#{operator},#{reason},'PROPOSED',1)") int insert(Map<String,Object> row);
    @Update("update station_aftercare set proposed_amount=#{amount},proposed_station_id=#{station},proposed_by=#{operator},reason=#{reason},version=version+1 where refund_payment_id=#{payment} and version=#{version} and status='PROPOSED'") int propose(Map<String,Object> row);
    @Update("update station_aftercare set status='AGREED',agreed_by=#{operator},agreed_time=now(),version=version+1 where refund_payment_id=#{payment} and version=#{version} and status='PROPOSED' and proposed_station_id<>#{station}") int agree(Map<String,Object> row);
    @Update("update station_aftercare set status='PAID',paid_by=#{operator},paid_time=now(),version=version+1 where refund_payment_id=#{payment} and version=#{version} and status='AGREED' and other_station_id=#{station}") int paid(Map<String,Object> row);
    @Select("select * from station_aftercare_action where refund_payment_id=#{payment} and actor_key=#{actor} and request_key=#{key}") Map<String,Object> action(@Param("payment") Long payment,@Param("actor") String actor,@Param("key") String key);
    @Select("select id,actor_key as actorKey,request_key as requestKey,action,case action when 'PROPOSE' then '提出责任方案' when 'AGREE' then '双方核定责任' when 'PAID' then '登记实际交付' else '待核对记录' end as actionText,amount,reason,from_version as fromVersion,to_version as toVersion,create_time as createTime from station_aftercare_action where refund_payment_id=#{payment} order by id") List<Map<String,Object>> history(Long payment);
    @Insert("insert into station_aftercare_action(refund_payment_id,actor_key,request_key,request_digest,action,amount,reason,from_version,to_version) values(#{payment},#{actor},#{key},#{digest},#{action},#{amount},#{reason},#{version},#{version}+1)")
    @Options(useGeneratedKeys=true,keyProperty="id") int append(Map<String,Object> row);
}
