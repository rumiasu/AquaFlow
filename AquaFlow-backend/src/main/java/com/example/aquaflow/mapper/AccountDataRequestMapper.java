package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.AccountDataRequest;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** The person is scoped in every read; duplicate inserts leave the existing request unchanged. */
@Mapper
public interface AccountDataRequestMapper {
    @Select("select count(*) from customer where id=#{id}") int customerExists(@Param("id") Long id);
    @Select("select count(*) from staff where id=#{id}") int staffExists(@Param("id") Long id);
    @Select("select * from account_data_request where actor_type=#{actor} and actor_id=#{id} and idempotency_key=#{key}")
    AccountDataRequest findReplay(@Param("actor") String actor,@Param("id") Long id,@Param("key") String key);
    @Select("select * from account_data_request where actor_type=#{actor} and actor_id=#{id} and idempotency_key=#{key} for update")
    AccountDataRequest findReplayForUpdate(@Param("actor") String actor,@Param("id") Long id,@Param("key") String key);
    @Insert("insert into account_data_request(actor_type,actor_id,request_type,note,idempotency_key,request_digest,status,create_time) "
            + "values(#{actorType},#{actorId},#{requestType},#{note},#{idempotencyKey},#{requestDigest},'SUBMITTED',#{createTime}) "
            + "on duplicate key update id=last_insert_id(id)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(AccountDataRequest request);
    @Select("select * from account_data_request where id=#{requestId} and actor_type=#{actor} and actor_id=#{id}")
    AccountDataRequest mine(@Param("requestId") Long requestId,@Param("actor") String actor,@Param("id") Long id);
    @Select("<script>select * from account_data_request where actor_type=#{actor} and actor_id=#{id} "
            + "<if test='beforeId != null'>and id &lt; #{beforeId}</if> order by id desc limit 51</script>")
    List<AccountDataRequest> listMine(@Param("actor") String actor,@Param("id") Long id,@Param("beforeId") Long beforeId);
}
