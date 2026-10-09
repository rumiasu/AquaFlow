package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** 只写争议沟通状态及审计；原申请/订单/原款只作为锁锚。 */
@Mapper
public interface RefundDisputeMapper {
    @Select("select id from barrel_record where id=#{id} for update") Long lockReturn(Long id);
    @Select("select refund_type as refundType,refund_id as refundId,customer_id as customerId,responsible_station_id as responsibleStationId,status,version,last_result as lastResult,update_time as updateTime from refund_dispute where refund_type=#{type} and refund_id=#{id}")
    Map<String,Object> get(@Param("type") String type,@Param("id") Long id);
    @Insert("insert into refund_dispute(refund_type,refund_id,customer_id,responsible_station_id,status,version,update_time) values(#{type},#{id},#{customer},#{station},'OPEN',1,now())")
    int open(@Param("type") String type,@Param("id") Long id,@Param("customer") Long customer,@Param("station") Long station);
    @Update("update refund_dispute set status=#{target},version=version+1,last_result=case when #{target}='CLOSED' then #{reason} else last_result end,update_time=now() where refund_type=#{type} and refund_id=#{id} and version=#{version} and status=#{expected} and customer_id=#{customer} and responsible_station_id=#{station}")
    int transition(@Param("type") String type,@Param("id") Long id,@Param("customer") Long customer,@Param("station") Long station,@Param("version") long version,@Param("expected") String expected,@Param("target") String target,@Param("reason") String reason);
    @Insert("insert into refund_dispute_action(refund_type,refund_id,customer_id,responsible_station_id,actor_key,operator_id,action,reason,idempotency_key,request_digest,from_version,to_version,create_time) values(#{type},#{id},#{customer},#{station},#{actor},#{operator},#{action},#{reason},#{key},#{digest},#{version},#{version}+1,now())")
    int appendAction(@Param("type") String type,@Param("id") Long id,@Param("customer") Long customer,@Param("station") Long station,@Param("actor") String actor,@Param("operator") Long operator,
                     @Param("action") String action,@Param("reason") String reason,@Param("key") String key,@Param("digest") String digest,@Param("version") long version);
    @Select("select id,refund_type as refundType,refund_id as refundId,action,reason,operator_id as operatorId,request_digest as requestDigest,from_version as fromVersion,to_version as toVersion,create_time as createTime from refund_dispute_action where actor_key=#{actor} and refund_type=#{type} and refund_id=#{id} and idempotency_key=#{key}")
    Map<String,Object> action(@Param("actor") String actor,@Param("type") String type,@Param("id") Long id,@Param("key") String key);
    @Select("select id,refund_type as refundType,refund_id as refundId,action,reason,operator_id as operatorId,from_version as fromVersion,to_version as toVersion,create_time as createTime from refund_dispute_action where refund_type=#{type} and refund_id=#{id} and customer_id=#{customer} and responsible_station_id=#{station} order by id")
    List<Map<String,Object>> actions(@Param("type") String type,@Param("id") Long id,@Param("customer") Long customer,@Param("station") Long station);
    @Select("<script>select refund_type as refundType,refund_id as refundId,customer_id as customerId,responsible_station_id as responsibleStationId,status,version,last_result as lastResult,update_time as updateTime from refund_dispute where <choose><when test='customer != null'>customer_id=#{customer}</when><otherwise>responsible_station_id=#{station}</otherwise></choose> order by case when status='OPEN' then 0 else 1 end,update_time desc,refund_type,refund_id desc limit #{offset},200</script>")
    List<Map<String,Object>> list(@Param("customer") Long customer,@Param("station") Long station,@Param("offset") int offset);
}
