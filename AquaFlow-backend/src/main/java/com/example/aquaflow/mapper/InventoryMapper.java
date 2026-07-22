package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Inventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface InventoryMapper {

    List<Inventory> list();

    @Insert("insert into inventory (water_type_id, station_id, quantity) values (#{waterTypeId},#{stationId},#{quantity})")
    void save(Inventory inventory);

    Inventory getByStationAndWaterType(@Param("stationId") Integer stationId, @Param("waterTypeId") Integer waterTypeId);

    void insert(Inventory newInventory);

    void update(Inventory inventory);

    /** 原子扣减库存，带库存不足保护（不会扣成负数） */
    @Update("update inventory set quantity = quantity - #{quantity}, update_time = now() " +
            "where station_id = #{stationId} and water_type_id = #{waterTypeId} and quantity >= #{quantity}")
    int decreaseStock(@Param("stationId") Integer stationId, @Param("waterTypeId") Integer waterTypeId, @Param("quantity") Integer quantity);

    @Update("update inventory set quantity = quantity + #{quantity}, update_time = now() " +
            "where station_id = #{stationId} and water_type_id = #{waterTypeId}")
    void increaseStock(@Param("stationId") Integer stationId, @Param("waterTypeId") Integer waterTypeId, @Param("quantity") Integer quantity);

    @Select("select ifnull(sum(quantity), 0) from inventory")
    int sumQuantity();

    // ========== 水厂运营平台统计查询 ==========

    @Select("select station_id as stationId, ifnull(sum(quantity), 0) as totalQuantity from inventory where station_id is not null group by station_id")
    List<Map<String, Object>> sumQuantityByStation();

    @Select("select i.*, w.name as waterTypeName, w.spec as spec, s.name as stationName from inventory i left join water_type w on i.water_type_id = w.id left join station s on i.station_id = s.id where i.station_id is not null")
    List<Inventory> listWithStation();

    @Select("select i.*, w.name as waterTypeName, w.spec as spec from inventory i left join water_type w on i.water_type_id = w.id where i.station_id = #{stationId}")
    List<Inventory> listByStationId(@Param("stationId") Integer stationId);

    @Select("select count(*) from inventory where station_id is not null and quantity < 20")
    int countLowStockByStation();

    @Select("select station_id as stationId, count(*) as lowCount from inventory where station_id is not null and quantity < 20 group by station_id")
    List<Map<String, Object>> lowStockByStation();

}
