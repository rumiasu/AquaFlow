package com.example.aquaflow.mapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.math.BigDecimal;
@Mapper
public interface StationRecoveryMapper {
    @Select("select coalesce(sum(r.amount * -1),0) from consumption_refund c join payment_record r on r.id=c.refund_payment_id where c.order_id=#{order} and r.station_id=#{station} and r.payment_method=2")
    BigDecimal refundedByReceiver(@Param("order") Long order,@Param("station") Long station);
    @Select("select * from inter_station_settlement where order_id=#{id} for update") com.example.aquaflow.entity.InterStationSettlement lockSettlement(Long id);
    @Insert("insert into inter_station_recovery(order_id,from_station_id,to_station_id,amount,status,note,create_time) values(#{order},#{from},#{to},#{amount},'PENDING',#{note},now())")
    int insert(@Param("order") Long order,@Param("from") Long from,@Param("to") Long to,@Param("amount") BigDecimal amount,@Param("note") String note);
    @Select("select * from inter_station_recovery where from_station_id=#{station} or to_station_id=#{station} order by create_time desc limit 200") List<Map<String,Object>> list(Long station);
    @Update("update inter_station_recovery set sender_confirmed_by=#{operator},sender_confirmed_time=now(),note=#{note} where order_id=#{order} and from_station_id=#{station} and status='PENDING' and sender_confirmed_time is null")
    int sent(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator,@Param("note") String note);
    @Update("update inter_station_recovery set status='RECEIVED',receiver_confirmed_by=#{operator},receiver_confirmed_time=now() where order_id=#{order} and to_station_id=#{station} and status='PENDING' and sender_confirmed_time is not null")
    int received(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator);
}
