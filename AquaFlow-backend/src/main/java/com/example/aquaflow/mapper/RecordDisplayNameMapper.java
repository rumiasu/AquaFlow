package com.example.aquaflow.mapper;

import com.example.aquaflow.dto.RecordDisplayNameRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecordDisplayNameMapper {
    // 2026-10-24：异常属于本站不等于可读他站客户画像；跨站仅保留有权履约订单的收件人快照。
    @Select("<script>select e.id,c.name as customer_name,o.receiver_name as receiver_name"
            + " from order_barrel_exception e"
            + " left join orders o on o.id=e.order_id and o.customer_id=e.customer_id"
            + " and (o.station_id=#{stationId} or o.delivery_station_id=#{stationId})"
            + " left join customer c on c.id=e.customer_id and o.station_id=#{stationId}"
            + " where e.station_id=#{stationId} and "
            + "<choose><when test='ids != null and !ids.isEmpty()'>e.id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>1=0</otherwise></choose></script>")
    List<RecordDisplayNameRow> exceptionNames(@Param("stationId") Long stationId, @Param("ids") List<Long> ids);

    // 客户画像仍认绑定∪本站订单，私有商品仍认owner_station_id；没有名称不影响原记录返回。
    // 只补资产退还(type=2)，避免把跨站配送流水变成新的客户画像读取面。
    @Select("<script>select r.id,c.name as customer_name,p.name as product_name,p.spec as product_spec,s.name as station_name"
            + " from barrel_record r"
            + " left join customer c on c.id=r.customer_id and ("
            + " exists(select 1 from customer_station_config cfg where cfg.customer_id=c.id and cfg.station_id=#{stationId})"
            + " or exists(select 1 from orders own_order where own_order.customer_id=c.id and own_order.station_id=#{stationId}))"
            + " left join product p on p.id=r.product_id and (p.owner_station_id is null or p.owner_station_id=#{stationId})"
            + " left join station s on s.id=r.station_id"
            + " where r.station_id=#{stationId} and r.type=2 and "
            + "<choose><when test='ids != null and !ids.isEmpty()'>r.id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>1=0</otherwise></choose></script>")
    List<RecordDisplayNameRow> barrelNames(@Param("stationId") Long stationId, @Param("ids") List<Long> ids);
}
