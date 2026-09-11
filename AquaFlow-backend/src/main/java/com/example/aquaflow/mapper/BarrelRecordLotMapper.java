package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRecordLot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BarrelRecordLotMapper {

    @org.apache.ibatis.annotations.Insert("insert into barrel_record_lot(record_id, lot_id, qty, unit_price, amount) " +
            "values(#{recordId}, #{lotId}, #{qty}, #{unitPrice}, #{amount})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BarrelRecordLot row);

    @Select("select * from barrel_record_lot where record_id = #{recordId}")
    List<BarrelRecordLot> listByRecordId(@Param("recordId") Long recordId);

    @Select("select * from barrel_record_lot where lot_id = #{lotId}")
    List<BarrelRecordLot> listByLotId(@Param("lotId") Long lotId);
}
