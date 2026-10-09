package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.math.BigDecimal;
@Mapper
public interface StationRecoveryMapper {
    @Select("select coalesce(sum(-(amount-coalesce(barrel_deposit,0))),0) from payment_record where order_id=#{order} and station_id=#{station} and payment_method=2 and status=3 and amount<0")
    BigDecimal refundedByReceiver(@Param("order") Long order,@Param("station") Long station);
    @Select("select coalesce(sum(amount-coalesce(barrel_deposit,0)),0) from payment_record where order_id=#{order} and station_id=#{station} and payment_method=2 and amount>0 and status in (2,3)")
    BigDecimal customerCashHeld(@Param("order") Long order,@Param("station") Long station);
    @Select("select * from payment_record where order_id=#{order} and station_id=#{station} and payment_method=2 and status in (2,3) order by id for update")
    List<com.example.aquaflow.entity.PaymentRecord> cashForUpdate(@Param("order") Long order,@Param("station") Long station);
    @Select("select max(station_id) from payment_record where order_id=#{order} and payment_method=2 and amount>0 and status in (2,3)")
    Long cashCollectionStation(Long order);
    @Select("select from_station_id as fromStationId,to_station_id as toStationId,amount from inter_station_recovery where order_id=#{order} for update")
    Map<String,Object> lockRecovery(Long order);
    @Select("select * from inter_station_settlement where order_id=#{id} for update") com.example.aquaflow.entity.InterStationSettlement lockSettlement(Long id);
    @Insert("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,note,create_time) values(#{order},#{from},#{to},#{amount},'PENDING',#{note},now())")
    int insert(@Param("order") Long order,@Param("from") Long from,@Param("to") Long to,@Param("amount") BigDecimal amount,@Param("note") String note);
    @Select("select * from inter_station_recovery where from_station_id=#{station} or to_station_id=#{station} order by create_time desc limit 200") List<Map<String,Object>> list(Long station);
    @Update("update inter_station_recovery set sender_confirmed_by=#{operator},sender_confirmed_time=now(),note=#{note} where order_id=#{order} and from_station_id=#{station} and status='PENDING' and sender_confirmed_time is null")
    int sent(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator,@Param("note") String note);
    @Update("update inter_station_recovery set status='RECEIVED',receiver_confirmed_by=#{operator},receiver_confirmed_time=now() where order_id=#{order} and to_station_id=#{station} and status='PENDING' and sender_confirmed_time is not null")
    int received(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator);
}
