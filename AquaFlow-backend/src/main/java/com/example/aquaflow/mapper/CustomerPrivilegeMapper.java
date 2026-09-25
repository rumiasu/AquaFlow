package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 客户特权（v40）。规格见 {@code docs/design/20} §4。
 *
 * <p>⚠️ <b>本表只承载"不动钱"的特权类型</b>。动钱的（折扣率/免配送次数/允许退票）
 * 需要账户 + 流水，落在本表表达不了语义 —— 授予时由 {@code PrivilegeType.isImplemented}
 * 在接口层拒绝，别在这里"顺手支持一下"。</p>
 */
@Mapper
public interface CustomerPrivilegeMapper {

    /**
     * 授予（有则更新备注，不重复插入）。
     *
     * <p>用 upsert 而不是"先查再插"：并发两次授予会双双查不到、双双 insert 撞唯一键。</p>
     *
     * @return 受影响行数（1=新增，2=更新，0=值完全相同）
     */
    @Insert("insert into customer_privilege(customer_id, station_id, type, value, note, operator_id, create_time, update_time) "
            + "values(#{customerId}, #{stationId}, #{type}, #{value}, #{note}, #{operatorId}, NOW(), NOW()) "
            + "on duplicate key update value=values(value), note=values(note), operator_id=values(operator_id), update_time=NOW()")
    int upsert(@Param("customerId") Long customerId, @Param("stationId") Long stationId,
               @Param("type") String type, @Param("value") String value,
               @Param("note") String note, @Param("operatorId") Long operatorId);

    /**
     * 撤销特权。
     *
     * @return 受影响行数；<b>0 = 该客户本来就没有这项特权</b>，调用方必须据此报错而不是
     *         无条件返回成功（本仓在"删别人的地址却返回成功"上踩过，AGENTS §8.20）
     */
    @Delete("delete from customer_privilege where customer_id = #{customerId} "
            + "and station_id = #{stationId} and type = #{type}")
    int revoke(@Param("customerId") Long customerId, @Param("stationId") Long stationId,
               @Param("type") String type);

    /** 某客户在本站的全部特权（站长端客户画像用） */
    @Select("select * from customer_privilege where customer_id = #{customerId} and station_id = #{stationId} "
            + "order by type asc")
    List<Map<String, Object>> listByCustomer(@Param("customerId") Long customerId,
                                             @Param("stationId") Long stationId);

    /**
     * 是否拥有某项特权 —— <b>计费/下单链路上的唯一判定入口</b>。
     *
     * <p>返回 int 而不是 boolean：MyBatis 对 boolean 的映射依赖驱动，用 count 更稳。</p>
     */
    @Select("select count(*) from customer_privilege where customer_id = #{customerId} "
            + "and station_id = #{stationId} and type = #{type}")
    int countByType(@Param("customerId") Long customerId, @Param("stationId") Long stationId,
                    @Param("type") String type);

    /** 本站授予过该项特权的客户数（站长端概览用） */
    @Select("select count(*) from customer_privilege where station_id = #{stationId} and type = #{type}")
    int countByStationAndType(@Param("stationId") Long stationId, @Param("type") String type);
}
