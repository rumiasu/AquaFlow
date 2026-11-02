package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** Append-only case decisions. Original case row locks serialize version and receipt checks. */
@Mapper
public interface CaseHandlingMapper {
    @Select("select count(*) from information_schema.tables where table_schema=database() and table_name='case_handling_action' and engine='InnoDB'")
    int schemaReady();
    @Select("select * from feedback where id=#{id} for update")
    com.example.aquaflow.entity.Feedback lockFeedback(Long id);

    @Select("select count(*) from customer where id=#{customer} and (exists(select 1 from customer_station_config c where c.customer_id=#{customer} and c.station_id=#{station}) or exists(select 1 from orders o where o.customer_id=#{customer} and o.station_id=#{station}))")
    int related(@Param("customer") Long customer,@Param("station") Long station);

    @Select("select id,kind,case_id as caseId,station_id as stationId,version,action,reason,request_digest as requestDigest from case_handling_action where kind=#{kind} and case_id=#{id} and station_id=#{station} and actor_id=#{actor} and idempotency_key=#{key}")
    Map<String,Object> receipt(@Param("kind") String kind,@Param("id") Long id,@Param("station") Long station,@Param("actor") Long actor,@Param("key") String key);

    @Select("<script>select a.id,a.kind,a.case_id as caseId,a.station_id as stationId,s.name as stationName,a.version,a.action,a.reason,a.create_time as createTime from case_handling_action a left join station s on s.id=a.station_id where a.kind=#{kind} and a.case_id=#{id} <if test='station != null'>and a.station_id=#{station}</if> order by a.id</script>")
    List<Map<String,Object>> history(@Param("kind") String kind,@Param("id") Long id,@Param("station") Long station);

    @Insert("insert into case_handling_action(kind,case_id,station_id,actor_id,version,action,reason,idempotency_key,request_digest,create_time) values(#{kind},#{id},#{station},#{actor},#{version},#{action},#{reason},#{key},#{digest},now())")
    int append(@Param("kind") String kind,@Param("id") Long id,@Param("station") Long station,@Param("actor") Long actor,@Param("version") long version,@Param("action") String action,@Param("reason") String reason,@Param("key") String key,@Param("digest") String digest);
}
