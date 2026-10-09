package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** 拒付证据与资产冻结授权；欠款是否有效仍以订单支付状态为准。 */
@Mapper
public interface ConfirmedRefusalMapper {
    @Insert("insert into customer_refusal_case(order_id,exception_id,customer_id,asset_station_id,debt_station_id,asset_freeze_confirmed,operator_id,note,create_time) values(#{order},#{exception},#{customer},#{asset},#{debt},#{freeze},#{operator},#{note},now())")
    int insert(@Param("order") Long order,@Param("exception") Long exception,@Param("customer") Long customer,
               @Param("asset") Long asset,@Param("debt") Long debt,@Param("freeze") boolean freeze,@Param("operator") Long operator,@Param("note") String note);
    @Select("select r.* from customer_refusal_case r join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id where r.customer_id=#{customer} and coalesce(x.judgment_revoked,0)=0 and o.payment_status=1 and o.status<>5 and (r.debt_station_id=#{station} or r.asset_station_id=#{station}) order by r.order_id for update")
    List<Map<String,Object>> activeForUpdate(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select count(*) from customer_refusal_case r join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id where r.customer_id=#{customer} and r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and coalesce(x.asset_freeze_released,0)=0 and o.payment_status=1 and o.status<>5")
    int frozen(@Param("customer") Long customer,@Param("station") Long station);
    @Select("select distinct r.customer_id as customerId from customer_refusal_case r join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id where r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and coalesce(x.asset_freeze_released,0)=0 and o.payment_status=1 and o.status<>5")
    List<Map<String,Object>> frozenCustomers(@Param("station") Long station);
    @Select("select r.*,o.total_amount as debtAmount,o.payment_status as paymentStatus,o.status as orderStatus,coalesce(x.judgment_revoked,0) as judgmentRevoked,coalesce(x.asset_freeze_released,0) as assetFreezeReleased,coalesce(x.version,0) as version from customer_refusal_case r join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id where r.asset_station_id=#{station} or r.debt_station_id=#{station} order by r.create_time desc limit 200")
    List<Map<String,Object>> list(@Param("station") Long station);
    /** 当前事项同时保留实际限制及本站仍可执行的纠错动作；已结历史另可遍历/编号定位。 */
    @Select("<script>select r.*,o.total_amount as debtAmount,o.payment_status as paymentStatus,o.status as orderStatus,"
            + "coalesce(x.judgment_revoked,0) as judgmentRevoked,coalesce(x.asset_freeze_released,0) as assetFreezeReleased,"
            + "coalesce(x.version,0) as version from customer_refusal_case r join orders o on o.id=r.order_id"
            + " left join customer_refusal_resolution x on x.order_id=r.order_id"
            + " where (r.asset_station_id=#{station} or r.debt_station_id=#{station})"
            + "<if test='order != null'> and r.order_id=#{order}</if>"
            + "<if test='order == null and active'> and ((o.payment_status=1 and o.status!=5 and coalesce(x.judgment_revoked,0)=0)"
            + " or (r.debt_station_id=#{station} and coalesce(x.judgment_revoked,0)=0)"
            + " or (r.asset_station_id=#{station} and r.asset_freeze_confirmed=1 and coalesce(x.asset_freeze_released,0)=0))</if>"
            + "<if test='before != null'> and r.order_id &lt; #{before}</if> order by r.order_id desc limit #{limit}</script>")
    List<Map<String,Object>> page(@Param("station") Long station, @Param("before") Long before,
            @Param("active") boolean active, @Param("order") Long order, @Param("limit") int limit);
    // [2026-10-08] 原先只比较冻结标记，迟到确认会在已还清/已取消后新增冻结事实。
    // 与原订单一起做当前状态CAS；先查后改仍会与收款竞争，不能拆成普通SELECT。
    @Update("update customer_refusal_case r join orders o on o.id=r.order_id left join customer_refusal_resolution x on x.order_id=r.order_id "
            + "set r.asset_freeze_confirmed=1,r.asset_confirmed_by=#{operator},r.asset_confirmed_time=now() "
            + "where r.order_id=#{order} and r.asset_station_id=#{station} and r.asset_freeze_confirmed=0 "
            + "and coalesce(x.judgment_revoked,0)=0 and coalesce(x.asset_freeze_released,0)=0 and o.payment_status=1 and o.status<>5")
    int confirmFreeze(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator);

    /** 原拒付行是所有纠错/解除的锁锚，新增状态行缺失时也不锁空索引间隙。 */
    @Select("select * from customer_refusal_case where order_id=#{order} for update")
    Map<String,Object> lockCase(Long order);
    @Select("select * from customer_refusal_case where order_id=#{order}")
    Map<String,Object> getCase(Long order);
    @Insert("insert into customer_refusal_resolution(order_id) values(#{order}) on duplicate key update order_id=order_id")
    int ensureResolution(Long order);
    @Select("select judgment_revoked as judgmentRevoked,asset_freeze_released as assetFreezeReleased,version from customer_refusal_resolution where order_id=#{order}")
    Map<String,Object> resolution(Long order);
    @Update("update customer_refusal_resolution set judgment_revoked=1,asset_freeze_released=case when #{sameStation} then 1 else asset_freeze_released end,version=version+1 where order_id=#{order} and version=#{version} and judgment_revoked=0")
    int revoke(@Param("order") Long order,@Param("version") long version,@Param("sameStation") boolean sameStation);
    @Update("update customer_refusal_resolution set asset_freeze_released=1,version=version+1 where order_id=#{order} and version=#{version} and asset_freeze_released=0")
    int release(@Param("order") Long order,@Param("version") long version);
    @Insert("insert into customer_refusal_action(order_id,station_id,operator_id,actor_key,action,reason,idempotency_key,request_digest,from_version,to_version,create_time) values(#{order},#{station},#{operator},#{actor},#{action},#{reason},#{key},#{digest},#{version},#{version}+1,now())")
    int appendAction(@Param("order") Long order,@Param("station") Long station,@Param("operator") Long operator,@Param("actor") String actor,@Param("action") String action,
                     @Param("reason") String reason,@Param("key") String key,@Param("digest") String digest,@Param("version") long version);
    @Select("select id,order_id as orderId,station_id as stationId,operator_id as operatorId,action,reason,request_digest as requestDigest,from_version as fromVersion,to_version as toVersion,create_time as createTime from customer_refusal_action where order_id=#{order} and actor_key=#{actor} and idempotency_key=#{key}")
    Map<String,Object> action(@Param("order") Long order,@Param("actor") String actor,@Param("key") String key);
    @Select("select id,order_id as orderId,station_id as stationId,operator_id as operatorId,action,reason,from_version as fromVersion,to_version as toVersion,create_time as createTime from customer_refusal_action where order_id=#{order} order by id")
    List<Map<String,Object>> actions(Long order);
}
