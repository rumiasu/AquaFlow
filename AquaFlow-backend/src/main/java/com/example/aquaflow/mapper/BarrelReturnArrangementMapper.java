package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelReturnDetail;
import org.apache.ibatis.annotations.*;
import java.util.*;

/** 原提交快照只插入；安排修改不触碰桶账、退款批次或原款关系。 */
@Mapper
public interface BarrelReturnArrangementMapper {
    @Insert("insert into barrel_return_arrangement(record_id,initial_pickup_mode,initial_companion_order_id) values(#{recordId},#{pickupMode},#{companionOrderId})")
    int insert(BarrelReturnDetail detail);

    @Update("update barrel_return_arrangement set version=version+1,requires_confirmation=#{required},confirmed_version=null where record_id=#{id} and version=#{expected}")
    int advance(@Param("id") Long id,@Param("expected") int expected,@Param("required") boolean required);

    @Select("select version,request_digest as requestDigest from barrel_return_arrangement_change where record_id=#{id} and actor_key=#{actor} and idempotency_key=#{key}")
    Map<String,Object> replay(@Param("id") Long id,@Param("actor") String actor,@Param("key") String key);

    @Select("select version,request_digest as requestDigest from barrel_return_arrangement_change where record_id=#{id} and actor_key=#{actor} and idempotency_key=#{key} for update")
    Map<String,Object> replayForUpdate(@Param("id") Long id,@Param("actor") String actor,@Param("key") String key);

    @Insert("insert into barrel_return_arrangement_change(record_id,version,actor_key,operator_id,idempotency_key,request_digest,reason,before_snapshot,after_snapshot,create_time) values(#{id},#{version},#{actor},#{operator},#{key},#{digest},#{reason},#{before},#{after},now())")
    int record(@Param("id") Long id,@Param("version") int version,@Param("actor") String actor,@Param("operator") Long operator,@Param("key") String key,@Param("digest") String digest,@Param("reason") String reason,@Param("before") String before,@Param("after") String after);
}
