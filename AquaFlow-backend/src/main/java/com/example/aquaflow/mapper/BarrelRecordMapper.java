package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BarrelRecordMapper {

    @Insert("insert into barrel_record(customer_id, station_id, product_id, type, quantity, related_order_id, note, operator_id, create_time, status, handle_note, deposit_refund) " +
            "values(#{customerId}, #{stationId}, #{productId}, #{type}, #{quantity}, #{relatedOrderId}, #{note}, #{operatorId}, #{createTime}, #{status}, #{handleNote}, #{depositRefund})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BarrelRecord barrelRecord);

    @Select("select * from barrel_record where id = #{id}")
    BarrelRecord getById(@Param("id") Long id);

    @Update("update barrel_record set status = #{status}, handle_note = #{handleNote} where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("handleNote") String handleNote);

    @Select("select * from barrel_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<BarrelRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from barrel_record where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId} order by create_time desc")
    List<BarrelRecord> listByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId, @Param("stationId") Long stationId);

    @Select("select * from barrel_record where station_id = #{stationId} order by create_time desc")
    List<BarrelRecord> listByStationId(@Param("stationId") Long stationId);
}
